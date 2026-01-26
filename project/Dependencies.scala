import sbt._

object Dependencies {
  // Tapir
  val tapirCore = "com.softwaremill.sttp.tapir" %% "tapir-core" % Versions.tapir
  val tapirJdkHttpServer =
    "com.softwaremill.sttp.tapir" %% "tapir-jdkhttp-server" % Versions.tapir
  val tapirHttp4sServer =
    "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % Versions.tapir
  val tapirJsonCirce =
    "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % Versions.tapir
  val tapirSwaggerUiBundle =
    "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % Versions.tapir

  // HTTP4s
  val http4sEmberServer =
    "org.http4s" %% "http4s-ember-server" % Versions.http4s
  val http4sEmberClient =
    "org.http4s" %% "http4s-ember-client" % Versions.http4s
  val http4sDsl = "org.http4s" %% "http4s-dsl" % Versions.http4s
  val http4sCirce = "org.http4s" %% "http4s-circe" % Versions.http4s

  // Circe
  val circeCore = "io.circe" %% "circe-core" % Versions.circe
  val circeGeneric = "io.circe" %% "circe-generic" % Versions.circe
  val circeParser = "io.circe" %% "circe-parser" % Versions.circe

  // Arrow
  val arrowFlight = "org.apache.arrow" % "arrow-flight" % Versions.arrow
  val flightSql = "org.apache.arrow" % "flight-sql" % Versions.arrow
  val arrowMemoryNetty =
    "org.apache.arrow" % "arrow-memory-netty" % Versions.arrow

  // gRPC
  val grpcNetty = "io.grpc" % "grpc-netty" % Versions.grpc
  val grpcStub = "io.grpc" % "grpc-stub" % Versions.grpc

  // Logging
  val scalaLogging =
    "com.typesafe.scala-logging" %% "scala-logging" % Versions.scalaLogging
  val logbackClassic =
    "ch.qos.logback" % "logback-classic" % Versions.logbackClassic

  // Configuration
  val pureconfigCore =
    "com.github.pureconfig" %% "pureconfig-core" % Versions.pureconfig
  val pureconfigGenericScala3 =
    "com.github.pureconfig" %% "pureconfig-generic-scala3" % Versions.pureconfig

  // JWT
  val javaJwt = "com.auth0" % "java-jwt" % Versions.javaJwt

  // Testing
  val scalaTest = "org.scalatest" %% "scalatest" % Versions.scalaTest % Test

  // JSQLParser
  val jsqlParser = "com.github.jsqlparser" % "jsqlparser" % Versions.jsqlParser
  val jsqlTranspiler =
    "ai.starlake.jsqltranspiler" % "jsqltranspiler" % Versions.jsqlTranspiler
  val starlakeJdbc = "ai.starlake.jdbc" % "starlakejdbc" % Versions.starlakeJdbc
}
