package solutions.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import scala.concurrent.duration.*
import solutions.domain.*
import solutions.protocol.DispatcherProtocol
import solutions.protocol.DispatcherProtocol.*

object Passenger {

  /* =========================
   * Internal messages
   * ========================= */

  private case object RequestRideTick
  private final case class MaybeCancel(rideId: String)

  private type Msg =
    DispatcherProtocol.RideResponse |
      DispatcherProtocol.ETAResponse |
      RequestRideTick.type |
      MaybeCancel

  /* =========================
   * Passenger behavior
   * ========================= */

  def apply(
    passengerId: String,
    dispatcher: ActorRef[DispatcherProtocol.Command]
  ): Behavior[DispatcherProtocol.RideResponse] =
    Behaviors.setup[Msg] { ctx =>
      Behaviors.withTimers[Msg] { timers =>

        // Periodically request rides
        timers.startTimerAtFixedRate(RequestRideTick, 5.seconds)

        idle(
          dispatcher = dispatcher,
          timers = timers,
          passengerId = passengerId
        )
      }
    }.narrow[DispatcherProtocol.RideResponse]

  /* =========================
   * States
   * ========================= */

  private def idle(
    dispatcher: ActorRef[DispatcherProtocol.Command],
    timers: TimerScheduler[Msg],
    passengerId: String
  ): Behavior[Msg] =
    Behaviors.receive { (ctx, msg) =>
      msg match {

        /* =========================
         * Periodic ride request
         * ========================= */

        case RequestRideTick =>
          ctx.log.info(s"Passenger $passengerId requesting ride")
          
          val pickup = randomCoord()
          val dropoff = randomCoord()

          dispatcher ! DispatcherProtocol.RequestRide(
            passengerId = passengerId,
            pickup = pickup,
            dropoff = dropoff,
            replyTo = ctx.self
          )

          Behaviors.same

        /* =========================
         * Ride accepted
         * ========================= */

        case RideAccepted(rideId, driverId, fare) =>
          ctx.log.info(
            s"Passenger $passengerId: ride $rideId accepted by driver $driverId, fare=$fare"
          )

          // Optionally cancel before pickup (20% chance)
          // for simplicity, we cancel after a fixed delay
          if (scala.util.Random.nextDouble() < 0.2) {
            timers.startSingleTimer(
              MaybeCancel(rideId),
              2.seconds
            )
          }

          inRide(dispatcher, timers, passengerId, rideId)

        /* =========================
         * Ride rejected
         * ========================= */
        // log ride rejection
        case RideRejected(reason) =>
          ctx.log.info(s"Passenger $passengerId: ride rejected ($reason)")
          Behaviors.same

        case _ =>
          Behaviors.same
      }
    }
  // Passenger is in a ride
  private def inRide(
    dispatcher: ActorRef[DispatcherProtocol.Command],
    timers: TimerScheduler[Msg],
    passengerId: String,
    rideId: String
  ): Behavior[Msg] =
    Behaviors.receive { (ctx, msg) =>
      msg match {

        /* =========================
         * Optional cancellation
         * ========================= */

        case MaybeCancel(rid) if rid == rideId =>
          dispatcher ! DispatcherProtocol.CancelRide(
            passengerId = passengerId,
            rideId = rideId
          )
          ctx.log.info(s"Passenger $passengerId cancelled ride $rideId")
          idle(dispatcher, timers, passengerId)

        /* =========================
         * Ignore new ride requests while busy
         * ========================= */

        case RequestRideTick =>
          Behaviors.same

        case _ =>
          Behaviors.same
      }
    }

  /* =========================
   * Helpers
   * ========================= */
// Generate a random coordinate within a 100x100 area
  private def randomCoord(): Coord =
    Coord(
      x = scala.util.Random.nextDouble() * 100,
      y = scala.util.Random.nextDouble() * 100
    )
}


