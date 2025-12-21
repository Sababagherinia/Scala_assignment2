package solutions.domain

/** 2D coordinate used for pickup, dropoff and driver locations */
final case class Coord(x: Double, y: Double):
  def distanceTo(other: Coord): Double =
    math.hypot(x - other.x, y - other.y)

/** Driver availability state */
enum Availability:
  case OnlineAvailable
  case OnlineBusy
  case Offline

/** Ride lifecycle states */
enum RideStatus:
  case Requested
  case Matching
  case Assigned
  case InProgress
  case Completed
  case Cancelled

/** Immutable ride description */
final case class Ride(
  rideId: String,
  passengerId: String,
  pickup: Coord,
  dropoff: Coord,
  status: RideStatus,
  driverId: Option[String],
  fare: Option[BigDecimal],
  requestedAtMillis: Long
)

/** Runtime driver metadata */
final case class DriverInfo(
  id: String,
  location: Coord,
  availability: Availability,
  ratingAverage: Double,
  ratingCount: Int,
  minFare: BigDecimal,
  earnings: BigDecimal
)

/** Passenger metadata */
final case class PassengerInfo(
  id: String,
  name: String,
  rating: Double
)
