package solutions

import akka.actor.typed.{ActorSystem, Behavior, SupervisorStrategy}
import akka.actor.typed.scaladsl.Behaviors
import scala.concurrent.duration.*
import akka.util.Timeout
import scala.concurrent.Await

import solutions.services.*
import solutions.actors.*
import solutions.domain.*
import solutions.protocol.{DispatcherProtocol, MonitorProtocol}
import akka.actor.typed.scaladsl.adapter.*
import akka.actor.typed.scaladsl.AskPattern.*


object Main  {

  // Store rideMonitor ref for querying later
  private var monitorRef: Option[akka.actor.typed.ActorRef[MonitorProtocol.Command]] = None

  def apply(): Behavior[Nothing] =
    Behaviors.setup[Nothing] { ctx =>

      /* =========================
       * Core services
       * ========================= */

      val rideMonitor =
        ctx.spawn(RideMonitor(), "ride-monitor")

      // Store reference for querying
      monitorRef = Some(rideMonitor)

      // Prepopulate RideMonitor with sample historical data for testing
      prepopulateRideMonitor(rideMonitor)

      val pricingService =
        ctx.spawn(PricingService(), "pricing-service")

      val bank =
        ctx.spawn(
          Bank(
            initialBalances = Map(
              "passenger-3"  -> BigDecimal(10),
              "passenger-7"  -> BigDecimal(5),
              "passenger-12" -> BigDecimal(8)
            ).withDefaultValue(BigDecimal(200))
          ),
          "bank"
        )

      val blacklist =
        ctx.spawn(PassengerBlacklist(), "passenger-blacklist")

      /* =========================
       * Dispatcher
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

  /**
   * Prepopulates the RideMonitor with sample historical data for testing.
   * This simulates rides that happened before the system started.
   * Creates peak hours at 8am (morning rush) and 6pm (evening rush).
   */
  private def prepopulateRideMonitor(monitor: akka.actor.typed.ActorRef[protocol.MonitorProtocol.Command]): Unit = {
    import protocol.MonitorProtocol.*
    import protocol.MonitorProtocol.RideEvent.*
    
    val now = System.currentTimeMillis()
    val oneDayAgo = now - (24 * 3600 * 1000)
    
    // Defining rush hours with more rides
    val rushHours = List(
      (8, 8),   // 8am - morning rush (8 rides)
      (18, 6),  // 6pm - evening rush (6 rides)
      (12, 4),  // noon - lunch (4 rides)
      (14, 3),  // 2pm (3 rides)
      (20, 3),  // 8pm (3 rides)
      (10, 2),  // 10am (2 rides)
      (16, 2),  // 4pm (2 rides)
      (22, 2)   // 10pm (2 rides)
    )
    
    var rideCounter = 1
    rushHours.foreach { case (hour, rideCount) =>
      (1 to rideCount).foreach { _ =>
        val rideId = s"historical-ride-$rideCounter"
        val passengerId = s"passenger-${((rideCounter - 1) % 15) + 1}"
        val driverId = s"driver-${((rideCounter - 1) % 10) + 1}"
        val fare = BigDecimal(15 + scala.util.Random.nextInt(30))
        val durationSeconds = 300 + scala.util.Random.nextInt(600) // 5-15 minutes
        
        // Set time to yesterday at the specified hour, with random minutes
        val minuteOffset = scala.util.Random.nextInt(60) * 60 * 1000
        val rideTime = oneDayAgo + (hour * 3600 * 1000) + minuteOffset
        
        monitor ! LogEvent(RideCompleted(
          rideId = rideId,
          driverId = driverId,
          passengerId = passengerId,
          fare = fare,
          durationSeconds = durationSeconds,
          timestampMillis = rideTime
        ))
        
        rideCounter += 1
      }
    }
  }

  def main(args: Array[String]): Unit = {
    val system = ActorSystem(Main(), "RideSharingSimulation")
    
    // Wait a bit for prepopulation and initial rides to complete
    println("\nWaiting for rides to complete...")
    Thread.sleep(5000)
    
    // Query analytics
    queryAnalytics(system)
    
    // Keep the system running
    println("\nPress Enter to stop...")
    scala.io.StdIn.readLine()
    println("Simulation ended")
    system.terminate()
  }
  
  private def queryAnalytics(system: ActorSystem[?]): Unit = {
    monitorRef.foreach { monitor =>
      implicit val timeout: Timeout = 3.seconds
      implicit val scheduler = system.scheduler
      
      try {
        val avgTime: MonitorProtocol.AverageRideTime = Await.result(
          monitor.ask(ref => MonitorProtocol.QueryAverageRideTime(ref)),
          3.seconds
        )
        
        val revenue: MonitorProtocol.TotalRevenue = Await.result(
          monitor.ask(ref => MonitorProtocol.QueryTotalRevenue(ref)),
          3.seconds
        )
        
        val busiestHour: MonitorProtocol.BusiestHour = Await.result(
          monitor.ask(ref => MonitorProtocol.QueryBusiestHour(ref)),
          3.seconds
        )
        
        val topDriver: MonitorProtocol.MostProfitableDriver = Await.result(
          monitor.ask(ref => MonitorProtocol.QueryMostProfitableDriver(ref)),
          3.seconds
        )
        
        println("\n" + "="*50)
        println("RIDE ANALYTICS DASHBOARD")
        println("="*50)
        println(f"Average Ride Time:    ${avgTime.averageSeconds}%.1f seconds")
        println(f"Total Revenue:         $$${revenue.amount}")
        println(f"Busiest Hour:          ${busiestHour.hour}%02d:00 (${busiestHour.rideCount} rides)")
        println(f"Top Driver:            ${topDriver.driverId} ($$${topDriver.earnings})")
        println("="*50 + "\n")
      } catch {
        case e: Exception =>
          println(s"Error querying analytics: ${e.getMessage}")
      }
    }
  }
}
