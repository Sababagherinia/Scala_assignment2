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
    Behaviors.receive { (_, msg) =>
      msg match {

        case BlacklistProtocol.IsBlacklisted(passengerId, rideId, replyTo) =>
          replyTo ! DispatcherProtocol.BlacklistCheckResult(
            rideId = rideId,
            passengerId = passengerId,
            allowed = !state.bannedPassengers.contains(passengerId)
          )
          Behaviors.same

        case BlacklistProtocol.Blacklist(passengerId, reason) =>
          // We ignore the reason for now, but keep it for extensibility
          running(state.copy(bannedPassengers = state.bannedPassengers + passengerId))
      }
    }
}


