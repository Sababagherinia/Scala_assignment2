package solutions

import akka.actor.typed.{ActorSystem, Behavior, SupervisorStrategy}
import akka.actor.typed.scaladsl.Behaviors
import scala.concurrent.duration.*

import solutions.services.*
import solutions.actors.*
import solutions.domain.*
import solutions.protocol.DispatcherProtocol
import akka.actor.typed.scaladsl.adapter.*


object Main  {

  def apply(): Behavior[Nothing] =
    Behaviors.setup[Nothing] { ctx =>

      /* =========================
       * Core services
       * ========================= */

      val rideMonitor =
        ctx.spawn(RideMonitor(), "ride-monitor")

      val pricingService =
        ctx.spawn(PricingService(), "pricing-service")

      val bank =
        ctx.spawn(
          Bank(
            initialBalances =
              Map.empty.withDefaultValue(BigDecimal(200))
          ),
          "bank"
        )

      val blacklist =
        ctx.spawn(PassengerBlacklist(), "passenger-blacklist")

      /* =========================
       * Dispatcher (supervised)
       * ========================= */

      val dispatcher =
        ctx.spawn(
          Behaviors
            .supervise(
              Dispatcher(
                pricingService = pricingService,
                bank = bank,
                blacklist = blacklist,
                monitor = rideMonitor
              )
            )
            .onFailure[Exception](SupervisorStrategy.restart),
          "dispatcher"
        )

      /* =========================
       * Spawn Drivers
       * ========================= */

      (1 to 10).foreach { i =>
        ctx.spawn(
          Driver(
            driverId = s"driver-$i",
            dispatcher = dispatcher,
            initialLocation = randomCoord(),
            minFare = BigDecimal(5 + scala.util.Random.nextInt(10))
          ),
          s"driver-$i"
        )
      }

      /* =========================
       * Spawn Passengers
       * ========================= */

      (1 to 15).foreach { i =>
        ctx.spawn(
          Passenger(
            passengerId = s"passenger-$i",
            dispatcher = dispatcher
          ),
          s"passenger-$i"
        )
      }

      ctx.log.info("Ride-sharing simulation started")

      Behaviors.empty
      
    }

  private def randomCoord(): Coord =
    Coord(
      x = scala.util.Random.nextDouble() * 100,
      y = scala.util.Random.nextDouble() * 100
    )

  def main(args: Array[String]): Unit = {
    val system = ActorSystem(Main(), "RideSharingSimulation")
    
    // Keep the system running
    println("Press Enter to stop...")
    scala.io.StdIn.readLine()
    println("Simulation ended")
    system.terminate()
  }
}
