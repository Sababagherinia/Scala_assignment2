package solutions.services

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import solutions.protocol.{BlacklistProtocol, DispatcherProtocol}

/**
 * PassengerBlacklist maintains a set of banned passengers.
 *
 * Passengers are blacklisted if they complete a ride
 * without sufficient funds.
 */
object PassengerBlacklist {

  /** Internal state */
  final case class State(
    bannedPassengers: Set[String]
  )

  def apply(): Behavior[BlacklistProtocol.Command] =
    running(State(Set.empty))

  private def running(state: State): Behavior[BlacklistProtocol.Command] =
    Behaviors.receive { (ctx, msg) =>
      msg match {

        case BlacklistProtocol.IsBlacklisted(passengerId, rideId, replyTo) =>
          val isBlacklisted = state.bannedPassengers.contains(passengerId)
          if (isBlacklisted) {
            ctx.log.warn(s"Blacklisted passenger $passengerId attempted to request ride $rideId")
          }
          replyTo ! DispatcherProtocol.BlacklistCheckResult(
            rideId = rideId,
            passengerId = passengerId,
            allowed = !isBlacklisted
          )
          Behaviors.same

        case BlacklistProtocol.Blacklist(passengerId, reason) =>
          ctx.log.warn(s"Passenger $passengerId added to blacklist: $reason")
          running(state.copy(bannedPassengers = state.bannedPassengers + passengerId))
      }
    }
}
