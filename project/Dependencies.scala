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

  // YAML
  val circeYaml = "io.circe" %% "circe-yaml-v12" % Versions.circeYaml

  // Cats
  val catsCore = "org.typelevel" %% "cats-core" % Versions.cats

  // JSQLParser
  // Source: https://mvnrepository.com/artifact/com.manticore-projects.jsqlformatter/jsqlparser
  val jsqlParser = "com.manticore-projects.jsqlformatter" % "jsqlparser" % Versions.jsqlParser
  val jsqlTranspiler =
    "ai.starlake.jsqltranspiler" % "jsqltranspiler" % Versions.jsqlTranspiler
  val starlakeJdbc = "ai.starlake.jdbc" % "starlakejdbc" % Versions.starlakeJdbc

  // DuckDB JDBC
  val duckdbJdbc = "org.duckdb" % "duckdb_jdbc" % Versions.duckdb

  // fs2-blobstore (multi-cloud ACL storage)
  val blobstoreCore = "com.github.fs2-blobstore" %% "core" % Versions.blobstore
  val blobstoreS3 = "com.github.fs2-blobstore" %% "s3" % Versions.blobstore
  val blobstoreGcs = "com.github.fs2-blobstore" %% "gcs" % Versions.blobstore
  val blobstoreAzure = "com.github.fs2-blobstore" %% "azure" % Versions.blobstore
  // Kubernetes
  val kubernetesClient =
    "io.fabric8" % "kubernetes-client" % Versions.fabric8

  // Authentication
  val nimbusJoseJwt = "com.nimbusds" % "nimbus-jose-jwt" % Versions.nimbusJoseJwt
  val hikariCp = "com.zaxxer" % "HikariCP" % Versions.hikariCp
  val jbcrypt = "at.favre.lib" % "bcrypt" % Versions.jbcrypt
}
