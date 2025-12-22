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
      running(State(initialBalances))
    }

  private def running(state: State): Behavior[BankProtocol.Command] =
    Behaviors.receive { (ctx, msg) =>
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
            ctx.log.warn(s"Payment failed for ride $rideId: INSUFFICIENT_FUNDS (passenger: $passengerId, balance: $passengerBalance, fare: $fare)")
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

            ctx.log.info(s"Payment successful for ride $rideId: passenger $passengerId paid $$${fare} to driver $driverId")
            
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
