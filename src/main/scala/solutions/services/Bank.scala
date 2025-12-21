package solutions.services

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import solutions.protocol.{BankProtocol, DispatcherProtocol}

/**
 * Bank actor:
 *  - maintains balances for passengers and drivers
 *  - handles payments after ride completion
 */
object Bank {

  /** Internal state */
  final case class State(
    balances: Map[String, BigDecimal]
  )

  /**
   * @param initialBalances initial balances for all accounts
   */
  def apply(initialBalances: Map[String, BigDecimal]): Behavior[BankProtocol.Command] =
    Behaviors.setup { _ =>
      running(State(initialBalances.withDefaultValue(BigDecimal(0))))
    }

  private def running(state: State): Behavior[BankProtocol.Command] =
    Behaviors.receive { (_, msg) =>
      msg match {

        case BankProtocol.ChargeAndPay(
              rideId,
              passengerId,
              driverId,
              fare,
              replyTo
            ) =>

          val passengerBalance = state.balances(passengerId)

          if (passengerBalance < fare) {
            // insufficient funds
            replyTo ! DispatcherProtocol.PaymentResult(
              rideId = rideId,
              success = false,
              reason = Some("INSUFFICIENT_FUNDS")
            )
            Behaviors.same
          } else {
            val driverBalance = state.balances(driverId)

            val updatedBalances =
              state.balances
                .updated(passengerId, passengerBalance - fare)
                .updated(driverId, driverBalance + fare)

            replyTo ! DispatcherProtocol.PaymentResult(
              rideId = rideId,
              success = true,
              reason = None
            )

            running(state.copy(balances = updatedBalances))
          }
      }
    }
}
