package solutions.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import scala.concurrent.duration.*
import solutions.domain.*
import solutions.protocol.DispatcherProtocol
import solutions.protocol.DriverProtocol

object Driver {

  /* =========================
   * Internal messages
   * ========================= */

  private case object LocationTick
  private final case class FinishRide(
    rideId: String,
    distance: Double
  )

  /* =========================
   * Driver behavior
   * ========================= */

  def apply(
    driverId: String,
    dispatcher: ActorRef[DispatcherProtocol.Command],
    initialLocation: Coord,
    minFare: BigDecimal
  ): Behavior[DriverProtocol.Command] = {
    type Msg = DriverProtocol.Command | LocationTick.type | FinishRide
    
    Behaviors.setup[Msg] { ctx =>
      Behaviors.withTimers[Msg] { timers =>

        // Register driver
        dispatcher ! DispatcherProtocol.RegisterDriver(
          driverId = driverId,
          ref = ctx.self,
          initialLocation = initialLocation,
          minFare = minFare
        )

        // Periodic location updates
        timers.startTimerAtFixedRate(LocationTick, 1.second)

          running(
            dispatcher,
            timers,
            driverId,
            location = initialLocation,
            available = true
          )
        }
      }.narrow[DriverProtocol.Command]
    }

  private def running(
    dispatcher: ActorRef[DispatcherProtocol.Command],
    timers: TimerScheduler[DriverProtocol.Command | LocationTick.type | FinishRide],
    driverId: String,
    location: Coord,
    available: Boolean
  ): Behavior[DriverProtocol.Command | LocationTick.type | FinishRide] =
    Behaviors.receive { (ctx, msg) =>
      msg match {

        /* =========================
         * Periodic location update
         * ========================= */

        case LocationTick =>
          if (available) {
            dispatcher ! DispatcherProtocol.LocationUpdate(
              driverId = driverId,
              location = location,
              timestampMillis = System.currentTimeMillis()
            )
          }
          Behaviors.same

        /* =========================
         * Ride offer
         * ========================= */

        case DriverProtocol.OfferRide(rideId, pickup, dropoff, fare, replyTo) =>
          val accept = scala.util.Random.nextDouble() < 0.8
          replyTo ! DispatcherProtocol.DriverDecision(
            rideId = rideId,
            driverId = driverId,
            accepted = accept
          )
          Behaviors.same

        /* =========================
         * Ride start
         * ========================= */

        case DriverProtocol.StartRide(rideId) =>
          ctx.log.info(s"Driver $driverId accepted ride $rideId")
          
          val travelDistance = pickupDistance(location)
          val travelTime = math.max(2, (travelDistance * 1.5).toInt)

          timers.startSingleTimer(
            FinishRide(rideId, travelDistance),
            travelTime.seconds
          )

          running(dispatcher, timers, driverId, location, available = false)

        /* =========================
         * Ride completion
         * ========================= */

        case FinishRide(rideId, _) =>
          ctx.log.info(s"Driver $driverId completed ride $rideId")
          
          val newLocation = Coord(
            location.x + scala.util.Random.nextDouble() * 10,
            location.y + scala.util.Random.nextDouble() * 10
          )

          dispatcher ! DispatcherProtocol.PaymentResult(
            rideId = rideId,
            success = true,
            reason = None
          )

          running(dispatcher, timers, driverId, newLocation, available = true)

        /* =========================
         * Ride cancelled
         * ========================= */

        case DriverProtocol.CancelRide(_) =>
          running(dispatcher, timers, driverId, location, available = true)
      }
    }

  private def pickupDistance(location: Coord): Double =
    scala.util.Random.nextDouble() * 10 + location.x.abs * 0.1
}

