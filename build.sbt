ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.7.4"

val tapirVersion = "1.11.10"

lazy val root = (project in file("."))
  .settings(
    name := "gizmo-on-demand",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-core" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-jdkhttp-server" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % tapirVersion, // Added from proxy
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % tapirVersion,

      // HTTP4s (from proxy)
      "org.http4s" %% "http4s-ember-server" % "0.23.24",
      "org.http4s" %% "http4s-ember-client" % "0.23.24",
      "org.http4s" %% "http4s-dsl" % "0.23.24",
      "org.http4s" %% "http4s-circe" % "0.23.24",

      // Circe
      "io.circe" %% "circe-core" % "0.14.10",
      "io.circe" %% "circe-generic" % "0.14.10",
      "io.circe" %% "circe-parser" % "0.14.10",

      // Arrow (from proxy)
      "org.apache.arrow" % "arrow-flight" % "14.0.1",
      "org.apache.arrow" % "flight-sql" % "14.0.1",
      "org.apache.arrow" % "arrow-memory-netty" % "14.0.1",

      // gRPC (from proxy)
      "io.grpc" % "grpc-netty" % "1.59.0",
      "io.grpc" % "grpc-stub" % "1.59.0",

      // Logging
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
      "ch.qos.logback" % "logback-classic" % "1.5.12",

      // Configuration (from proxy)
      "com.github.pureconfig" %% "pureconfig-core" % "0.17.8",
      "com.github.pureconfig" %% "pureconfig-generic-scala3" % "0.17.8",

      // Cats Effect (from proxy)
      "org.typelevel" %% "cats-effect" % "3.5.2",

      // JWT (from proxy)
      "com.auth0" % "java-jwt" % "4.4.0",

      // Testing
      "org.scalatest" %% "scalatest" % "3.2.17" % Test
    ),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "versions", "9", "module-info.class") =>
        MergeStrategy.discard
      case PathList("META-INF", xs @ _*) =>
        xs match {
          case "MANIFEST.MF" :: Nil => MergeStrategy.discard
          case "services" :: _      => MergeStrategy.concat
          case _                    => MergeStrategy.discard
        }
      case "module-info.class"       => MergeStrategy.discard
      case "application.conf"        => MergeStrategy.concat // Added
      case "reference.conf"          => MergeStrategy.concat
      case x if x.endsWith(".proto") => MergeStrategy.rename
      case _                         => MergeStrategy.first
    },
    assembly / assemblyOutputPath := baseDirectory.value / "distrib" / (assembly / assemblyJarName).value
  )
