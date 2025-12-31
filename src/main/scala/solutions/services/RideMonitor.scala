package solutions.services

import akka.actor.typed.Behavior
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import solutions.protocol.MonitorProtocol
import solutions.protocol.MonitorProtocol.{Command, RideEvent}

//  RideMonitor aggregates ride events for analytics.
object RideMonitor {

  //  Aggregated analytics state rebuilt by replaying RideEvent events. 
  final case class State(
    totalRevenue: BigDecimal,
    // hour -> completed ride count
    completedRidesByHour: Map[Int, Int],
    // driverId -> total earnings from completed rides
    driverEarnings: Map[String, BigDecimal],
    // driverId -> (sumRatings, count)
    driverRatings: Map[String, (Long, Long)],
    // Total duration and count for calculating average
    totalRideDuration: Long,
    totalCompletedRides: Long
  ) {

    def recordCompletedRide(tsMillis: Long, driverId: String, fare: BigDecimal, durationSeconds: Int): State = {
      val hour = RideMonitor.hourOf(tsMillis)
      val newCount = completedRidesByHour.getOrElse(hour, 0) + 1
      val newEarn = driverEarnings.getOrElse(driverId, BigDecimal(0)) + fare

      copy(
        totalRevenue = totalRevenue + fare,
        completedRidesByHour = completedRidesByHour.updated(hour, newCount),
        driverEarnings = driverEarnings.updated(driverId, newEarn),
        totalRideDuration = totalRideDuration + durationSeconds,
        totalCompletedRides = totalCompletedRides + 1
      )
    }

    def averageRideTime: Double = 
      if (totalCompletedRides == 0) 0.0
      else totalRideDuration.toDouble / totalCompletedRides

    def recordDriverRating(driverId: String, rating: Int): State = {
      val (sum, count) = driverRatings.getOrElse(driverId, (0L, 0L))
      copy(driverRatings = driverRatings.updated(driverId, (sum + rating.toLong, count + 1L)))
    }
  }

  object State {
    val empty: State = State(
      totalRevenue = BigDecimal(0),
      completedRidesByHour = Map.empty,
      driverEarnings = Map.empty,
      driverRatings = Map.empty,
      totalRideDuration = 0L,
      totalCompletedRides = 0L
    )
  }

  // Public factory method
  def apply(persistenceKey: String = "ride-monitor"): Behavior[Command] =
    EventSourcedBehavior[Command, RideEvent, State](
      persistenceId = PersistenceId.ofUniqueId(persistenceKey),
      emptyState = State.empty,
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
      // snapshot occasionally to speed up recovery
      .snapshotWhen { (_, _, seqNr) => seqNr % 200 == 0 }

  private val commandHandler: (State, Command) => Effect[RideEvent, State] =
    (state, cmd) =>
      cmd match {
        case MonitorProtocol.LogEvent(event) =>
          Effect.persist(event)

        case MonitorProtocol.QueryTotalRevenue(replyTo) =>
          replyTo ! MonitorProtocol.TotalRevenue(state.totalRevenue)
          Effect.none

        case MonitorProtocol.QueryBusiestHour(replyTo) =>
          if (state.completedRidesByHour.isEmpty) {
            replyTo ! MonitorProtocol.BusiestHour(hour = 0, rideCount = 0)
          } else {
            val (hour, count) = state.completedRidesByHour.maxBy(_._2)
            replyTo ! MonitorProtocol.BusiestHour(hour, count)
          }
          Effect.none

        case MonitorProtocol.QueryMostProfitableDriver(replyTo) =>
          if (state.driverEarnings.isEmpty) {
            replyTo ! MonitorProtocol.MostProfitableDriver(driverId = "", earnings = BigDecimal(0))
          } else {
            val (driverId, earnings) = state.driverEarnings.maxBy(_._2)
            replyTo ! MonitorProtocol.MostProfitableDriver(driverId, earnings)
          }
          Effect.none

        case MonitorProtocol.QueryAverageRideTime(replyTo) =>
          replyTo ! MonitorProtocol.AverageRideTime(state.averageRideTime)
          Effect.none
      }

  private val eventHandler: (State, RideEvent) => State =
    (state, evt) =>
      evt match {
        case RideEvent.RideCompleted(_, driverId, _, fare, durationSeconds, tsMillis) =>
          state.recordCompletedRide(tsMillis, driverId, fare, durationSeconds)

        case RideEvent.DriverRated(driverId, rating, _) =>
          state.recordDriverRating(driverId, rating)

        // For now we don’t aggregate other events, but we still persist them
        // so they can be used later.
        case _ =>
          state
      }

  private def hourOf(tsMillis: Long): Int =
    java.time.Instant
      .ofEpochMilli(tsMillis)
      .atZone(java.time.ZoneId.systemDefault())
      .getHour
}


