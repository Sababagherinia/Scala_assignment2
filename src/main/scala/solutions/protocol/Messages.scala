package solutions.protocol

import akka.actor.typed.ActorRef
import solutions.domain.*

/* ===========================
 * Dispatcher Protocol
 * =========================== */
object DispatcherProtocol:

  sealed trait Command

  // Passenger → Dispatcher
  final case class RequestRide(
    passengerId: String,
    pickup: Coord,
    dropoff: Coord,
    replyTo: ActorRef[RideResponse]
  ) extends Command

  final case class CancelRide(
    passengerId: String,
    rideId: String
  ) extends Command

  final case class GetETA(
    passengerId: String,
    rideId: String,
    replyTo: ActorRef[ETAResponse]
  ) extends Command

  // Driver → Dispatcher
  final case class LocationUpdate(
    driverId: String,
    location: Coord,
    timestampMillis: Long
  ) extends Command

  final case class RegisterDriver(
    driverId: String,
    ref: ActorRef[DriverProtocol.Command],
    initialLocation: Coord,
    minFare: BigDecimal
  ) extends Command

  final case class DriverOffline(driverId: String) extends Command

  final case class RideCompleted(
    rideId: String,
    driverId: String
  ) extends Command

  // Internal adapter responses
  final case class BlacklistCheckResult(
    rideId: String,
    passengerId: String,
    allowed: Boolean
  ) extends Command

  final case class FareQuoteResult(
    rideId: String,
    driverId: String,
    fare: BigDecimal,
    compatible: Boolean
  ) extends Command

  final case class DriverDecision(
    rideId: String,
    driverId: String,
    accepted: Boolean
  ) extends Command

  final case class PaymentResult(
    rideId: String,
    success: Boolean,
    reason: Option[String]
  ) extends Command

  // Passenger responses
  sealed trait RideResponse
  final case class RideAccepted(
    rideId: String,
    driverId: String,
    fare: BigDecimal
  ) extends RideResponse

  final case class RideRejected(reason: String) extends RideResponse

  sealed trait ETAResponse
  final case class ETA(seconds: Int) extends ETAResponse
  final case class ETAUnavailable(reason: String) extends ETAResponse


/* ===========================
 * Driver Protocol
 * =========================== */
object DriverProtocol:

  sealed trait Command

  final case class OfferRide(
    rideId: String,
    pickup: Coord,
    dropoff: Coord,
    fare: BigDecimal,
    replyTo: ActorRef[DispatcherProtocol.DriverDecision]
  ) extends Command

  final case class StartRide(rideId: String) extends Command
  final case class CancelRide(rideId: String) extends Command


/* ===========================
 * Pricing Service Protocol
 * =========================== */
object PricingProtocol:

  sealed trait Command

  final case class ComputeFare(
    rideId: String,
    pickup: Coord,
    dropoff: Coord,
    requestTimeMillis: Long,
    driverId: String,
    driverMinFare: BigDecimal,
    replyTo: ActorRef[DispatcherProtocol.FareQuoteResult]
  ) extends Command


/* ================
 * Bank Protocol
 * ================ */
object BankProtocol:

  sealed trait Command

  final case class ChargeAndPay(
    rideId: String,
    passengerId: String,
    driverId: String,
    fare: BigDecimal,
    replyTo: ActorRef[DispatcherProtocol.PaymentResult]
  ) extends Command


/* =============================
 * Passenger Blacklist Protocol
 * ============================= */
object BlacklistProtocol:

  sealed trait Command

  final case class IsBlacklisted(
    passengerId: String,
    rideId: String,
    replyTo: ActorRef[DispatcherProtocol.BlacklistCheckResult]
  ) extends Command

  final case class Blacklist(
    passengerId: String,
    reason: String
  ) extends Command


/* ========================================
 * Ride Monitor Protocol (Event Sourcing)
 * ======================================== */
object MonitorProtocol:

  sealed trait Command

  final case class LogEvent(event: RideEvent) extends Command

  final case class QueryTotalRevenue(replyTo: ActorRef[TotalRevenue]) extends Command
  final case class QueryBusiestHour(replyTo: ActorRef[BusiestHour]) extends Command
  final case class QueryMostProfitableDriver(replyTo: ActorRef[MostProfitableDriver]) extends Command
  final case class QueryAverageRideTime(replyTo: ActorRef[AverageRideTime]) extends Command

  // Persistent events
  enum RideEvent:
    case RideRequested(rideId: String, passengerId: String, timestampMillis: Long)
    case RideMatched(rideId: String, driverId: String, fare: BigDecimal, timestampMillis: Long)
    case RideStarted(rideId: String, timestampMillis: Long)
    case RideCompleted(
      rideId: String,
      driverId: String,
      passengerId: String,
      fare: BigDecimal,
      durationSeconds: Int,
      timestampMillis: Long
    )
    case RideCancelled(rideId: String, cancelledBy: String, timestampMillis: Long)
    case DriverRated(driverId: String, rating: Int, timestampMillis: Long)

  // Analytics responses
  final case class TotalRevenue(amount: BigDecimal)
  final case class BusiestHour(hour: Int, rideCount: Int)
  final case class MostProfitableDriver(driverId: String, earnings: BigDecimal)
  final case class AverageRideTime(averageSeconds: Double)
