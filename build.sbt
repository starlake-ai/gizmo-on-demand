ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.7.4"

lazy val root = (project in file("."))
  .settings(
    name := "gizmo-on-demand",
    resolvers ++= Resolvers.allResolvers,
    // Force Netty version compatible with Arrow 14.0.1.
    // Arrow's PooledByteBufAllocatorL accesses PoolArena.chunkSize via field inheritance
    // from SizeClasses. In Netty 4.1.104+, PoolArena no longer extends SizeClasses,
    // causing NoSuchFieldError at runtime. Fabric8 K8s client pulls Netty 4.1.117 via Vert.x.
    // Long-term fix: upgrade Arrow to 17.0+ (apache/arrow#39266).
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-buffer" % "4.1.96.Final",
      "io.netty" % "netty-common" % "4.1.96.Final",
      "io.netty" % "netty-handler" % "4.1.96.Final",
      "io.netty" % "netty-transport" % "4.1.96.Final",
      "io.netty" % "netty-codec" % "4.1.96.Final",
      "io.netty" % "netty-codec-http" % "4.1.96.Final",
      "io.netty" % "netty-codec-http2" % "4.1.96.Final",
      "io.netty" % "netty-codec-socks" % "4.1.96.Final",
      "io.netty" % "netty-handler-proxy" % "4.1.96.Final",
      "io.netty" % "netty-resolver" % "4.1.96.Final",
      "io.netty" % "netty-transport-native-unix-common" % "4.1.96.Final"
    ),
    libraryDependencies ++= Seq(
      Dependencies.tapirCore,
      Dependencies.tapirJdkHttpServer,
      Dependencies.tapirHttp4sServer,
      Dependencies.tapirJsonCirce,
      Dependencies.tapirSwaggerUiBundle,

      // HTTP4s (from proxy)
      Dependencies.http4sEmberServer,
      Dependencies.http4sEmberClient,
      Dependencies.http4sDsl,
      Dependencies.http4sCirce,

      // Circe
      Dependencies.circeCore,
      Dependencies.circeGeneric,
      Dependencies.circeParser,

      // Arrow (from proxy)
      Dependencies.arrowFlight,
      Dependencies.flightSql,
      Dependencies.arrowMemoryNetty,

      // gRPC (from proxy)
      Dependencies.grpcNetty,
      Dependencies.grpcStub,

      // Logging
      Dependencies.scalaLogging,
      Dependencies.logbackClassic,

      // Configuration (from proxy)
      Dependencies.pureconfigCore,
      Dependencies.pureconfigGenericScala3,

      // JWT (from proxy)
      Dependencies.javaJwt,

      // Testing
      Dependencies.scalaTest,

      // JSQLParser
      Dependencies.jsqlParser,
      Dependencies.jsqlTranspiler,
      Dependencies.starlakeJdbc,

      // Kubernetes
      Dependencies.kubernetesClient
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
