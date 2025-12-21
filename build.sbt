import scala.collection.Seq

ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.7.4"

lazy val root = (project in file("."))
  .settings(
    name := "Actors"
  )

resolvers += "Akka library repository" at "https://repo.akka.io/maven"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % "2.8.5",
  "com.typesafe.akka" %% "akka-persistence-testkit" % "2.8.5",
  "org.slf4j" % "slf4j-simple" % "2.0.16",
)
