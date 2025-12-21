package solutions.services

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import solutions.domain.*
import solutions.protocol.*
import solutions.protocol.DispatcherProtocol.*

object Dispatcher {

  /* =========================
   * Internal data structures
   * ========================= */

  final case class DriverEntry(
    ref: ActorRef[DriverProtocol.Command],
    location: Coord,
    available: Boolean,
    minFare: BigDecimal
  )

  final case class RideState(
    rideId: String,
    passengerId: String,
    pickup: Coord,
    dropoff: Coord,
    replyTo: ActorRef[RideResponse],
    candidates: List[String],
    selectedDriver: Option[String],
    fare: Option[BigDecimal]
  )

  final case class State(
    drivers: Map[String, DriverEntry],
    rides: Map[String, RideState]
  )

  /* =========================
   * Dispatcher behavior
   * ========================= */

  def apply(
    pricingService: ActorRef[PricingProtocol.Command],
    bank: ActorRef[BankProtocol.Command],
    blacklist: ActorRef[BlacklistProtocol.Command],
    monitor: ActorRef[MonitorProtocol.Command]
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      running(
        pricingService,
        bank,
        blacklist,
        monitor,
        State(drivers = Map.empty, rides = Map.empty)
      )
    }

  private def running(
    pricingService: ActorRef[PricingProtocol.Command],
    bank: ActorRef[BankProtocol.Command],
    blacklist: ActorRef[BlacklistProtocol.Command],
    monitor: ActorRef[MonitorProtocol.Command],
    state: State
  ): Behavior[Command] =
    Behaviors.receive { (ctx, msg) =>
      msg match {

        /* =========================
         * Driver registration & updates
         * ========================= */

        case RegisterDriver(driverId, ref, initialLocation, minFare) =>
          val entry = DriverEntry(ref, initialLocation, available = true, minFare)
          running(
            pricingService,
            bank,
            blacklist,
            monitor,
            state.copy(drivers = state.drivers.updated(driverId, entry))
          )

        case LocationUpdate(driverId, location, _) =>
          state.drivers.get(driverId) match {
            case Some(entry) =>
              val updated = entry.copy(location = location)
              running(
                pricingService,
                bank,
                blacklist,
                monitor,
                state.copy(drivers = state.drivers.updated(driverId, updated))
              )
            case None =>
              Behaviors.same
          }

        case DriverOffline(driverId) =>
          state.drivers.get(driverId) match {
            case Some(entry) =>
              val updated = entry.copy(available = false)
              running(
                pricingService,
                bank,
                blacklist,
                monitor,
                state.copy(drivers = state.drivers.updated(driverId, updated))
              )
            case None =>
              Behaviors.same
          }

        /* =========================
         * Ride request handling
         * ========================= */

        case RequestRide(passengerId, pickup, dropoff, replyTo) =>
          val rideId = java.util.UUID.randomUUID().toString

          monitor ! MonitorProtocol.LogEvent(
            MonitorProtocol.RideEvent.RideRequested(
              rideId,
              passengerId,
              System.currentTimeMillis()
            )
          )

          blacklist ! BlacklistProtocol.IsBlacklisted(
            passengerId,
            rideId,
            ctx.self
          )

          val newRide = RideState(
            rideId = rideId,
            passengerId = passengerId,
            pickup = pickup,
            dropoff = dropoff,
            replyTo = replyTo,
            candidates = Nil,
            selectedDriver = None,
            fare = None
          )

          running(
            pricingService,
            bank,
            blacklist,
            monitor,
            state.copy(rides = state.rides.updated(rideId, newRide))
          )

        case BlacklistCheckResult(rideId, passengerId, allowed) =>
          state.rides.get(rideId) match {
            case None => Behaviors.same

            case Some(ride) if !allowed =>
              ride.replyTo ! RideRejected("Passenger is blacklisted")
              running(
                pricingService,
                bank,
                blacklist,
                monitor,
                state.copy(rides = state.rides - rideId)
              )

            case Some(ride) =>
              val availableDrivers =
                state.drivers.toList
                  .filter(_._2.available)
                  .sortBy { case (_, d) => d.location.distanceTo(ride.pickup) }
                  .map(_._1)

              tryNextDriver(
                ctx,
                pricingService,
                bank,
                blacklist,
                monitor,
                state.copy(
                  rides = state.rides.updated(
                    rideId,
                    ride.copy(candidates = availableDrivers)
                  )
                ),
                rideId
              )
          }

        /* =========================
         * Pricing result
         * ========================= */

        case FareQuoteResult(rideId, driverId, fare, compatible) =>
          state.rides.get(rideId) match {
            case Some(ride) if compatible =>
              val driver = state.drivers(driverId)
              driver.ref ! DriverProtocol.OfferRide(
                rideId,
                ride.pickup,
                ride.dropoff,
                fare,
                ctx.self
              )

              running(
                pricingService,
                bank,
                blacklist,
                monitor,
                state.copy(
                  rides = state.rides.updated(
                    rideId,
                    ride.copy(selectedDriver = Some(driverId), fare = Some(fare))
                  )
                )
              )

            case _ =>
              tryNextDriver(
                ctx,
                pricingService,
                bank,
                blacklist,
                monitor,
                state,
                rideId
              )
          }

        /* =========================
         * Driver accept / reject
         * ========================= */

        case DriverDecision(rideId, driverId, accepted) =>
          state.rides.get(rideId) match {
            case Some(ride) if accepted =>
              monitor ! MonitorProtocol.LogEvent(
                MonitorProtocol.RideEvent.RideMatched(
                  rideId,
                  driverId,
                  ride.fare.get,
                  System.currentTimeMillis()
                )
              )

              ride.replyTo ! RideAccepted(rideId, driverId, ride.fare.get)

              val driver = state.drivers(driverId)
              driver.ref ! DriverProtocol.StartRide(rideId)

              Behaviors.same

            case _ =>
              tryNextDriver(
                ctx,
                pricingService,
                bank,
                blacklist,
                monitor,
                state,
                rideId
              )
          }

        /* =========================
         * Payment completion
         * ========================= */

        case PaymentResult(rideId, success, reason) =>
          if (!success) {
            val passengerId = state.rides(rideId).passengerId
            blacklist ! BlacklistProtocol.Blacklist(
              passengerId,
              reason.getOrElse("Payment failed")
            )
          }

          running(
            pricingService,
            bank,
            blacklist,
            monitor,
            state.copy(rides = state.rides - rideId)
          )

        /* =========================
         * Cancellation (minimal)
         * ========================= */

        case CancelRide(_, rideId) =>
          running(
            pricingService,
            bank,
            blacklist,
            monitor,
            state.copy(rides = state.rides - rideId)
          )

        case _ =>
          Behaviors.same
      }
    }

  /* =========================
   * Helper: try next driver
   * ========================= */

  private def tryNextDriver(
    ctx: akka.actor.typed.scaladsl.ActorContext[Command],
    pricingService: ActorRef[PricingProtocol.Command],
    bank: ActorRef[BankProtocol.Command],
    blacklist: ActorRef[BlacklistProtocol.Command],
    monitor: ActorRef[MonitorProtocol.Command],
    state: State,
    rideId: String
  ): Behavior[Command] = {

    state.rides.get(rideId) match {
      case Some(ride) if ride.candidates.nonEmpty =>
        val driverId = ride.candidates.head
        val driver = state.drivers(driverId)

        pricingService ! PricingProtocol.ComputeFare(
          rideId = rideId,
          pickup = ride.pickup,
          dropoff = ride.dropoff,
          requestTimeMillis = System.currentTimeMillis(),
          driverId = driverId,
          driverMinFare = driver.minFare,
          replyTo = ctx.self
        )

        running(
          pricingService,
          bank,
          blacklist,
          monitor,
          state.copy(
            rides = state.rides.updated(
              rideId,
              ride.copy(candidates = ride.candidates.tail)
            )
          )
        )

      case Some(ride) =>
        ride.replyTo ! RideRejected("No available drivers")
        running(
          pricingService,
          bank,
          blacklist,
          monitor,
          state.copy(rides = state.rides - rideId)
        )

      case None =>
        Behaviors.same
    }
  }
}
