package solutions.services

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import solutions.domain.Coord
import solutions.protocol.{DispatcherProtocol, PricingProtocol}


//  PricingService computes ride fares based on distance and time of day.

object PricingService {

  def apply(): Behavior[PricingProtocol.Command] =
    Behaviors.receive { (_, msg) =>
      msg match {

        case PricingProtocol.ComputeFare(
              rideId,
              pickup,
              dropoff,
              requestTimeMillis,
              driverId,
              driverMinFare,
              replyTo
            ) =>

          val distance = pickup.distanceTo(dropoff)
          val baseFare = BigDecimal(2.50)
          val perUnitDistance = BigDecimal(1.20)

          val rawFare = baseFare + perUnitDistance * BigDecimal(distance)

          val finalFare =
            if (isRushHour(requestTimeMillis)) rawFare * BigDecimal(1.5)
            else rawFare

          val roundedFare =
            finalFare.setScale(2, BigDecimal.RoundingMode.HALF_UP)

          val compatible = roundedFare >= driverMinFare

          replyTo ! DispatcherProtocol.FareQuoteResult(
            rideId = rideId,
            driverId = driverId,
            fare = roundedFare,
            compatible = compatible
          )

          Behaviors.same
      }
    }

  // Rush hours: 07–09 and 16–18
  private def isRushHour(tsMillis: Long): Boolean = {
    val hour =
      java.time.Instant
        .ofEpochMilli(tsMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .getHour

    (hour >= 7 && hour < 9) || (hour >= 16 && hour < 18)
  }
}
