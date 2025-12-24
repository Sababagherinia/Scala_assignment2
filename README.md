# Actors — Ride-Sharing Simulation (Akka Typed)

## Quick start
- Run: `sbt run`
- The program starts an Akka actor system and prints an “Analytics Dashboard” after a short delay.
- Press Enter to terminate.

## Project structure
- `src/main/scala/solutions/main.scala`: boots the system, spawns actors, queries analytics.
- `src/main/scala/solutions/actors`: Passenger and Driver actors.
- `src/main/scala/solutions/services`: Dispatcher + service actors (Bank, PricingService, PassengerBlacklist, RideMonitor).
- `src/main/scala/solutions/protocol/Messages.scala`: typed message protocols.
- `src/main/scala/solutions/domain/Models.scala`: immutable domain types.

## Architecture report
See [REPORT.md](REPORT.md) for:
- Component diagram
- Message flow sequence diagrams
- Architectural patterns: Forward Flow, Aggregator, Event Sourcing
