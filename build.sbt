ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.7.4"

lazy val root = (project in file("."))
  .settings(
    name := "gizmo-on-demand",
    resolvers ++= Resolvers.allResolvers,
    libraryDependencySchemes += "io.circe" %% "circe-yaml-common" % VersionScheme.Always,
    // Pin Netty to version compatible with arrow-memory-netty 14.x
    // (PooledByteBufAllocatorL accesses chunkSize field removed in Netty >= 4.1.100)
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-buffer"                          % Versions.netty,
      "io.netty" % "netty-common"                          % Versions.netty,
      "io.netty" % "netty-codec"                           % Versions.netty,
      "io.netty" % "netty-codec-http"                      % Versions.netty,
      "io.netty" % "netty-codec-http2"                     % Versions.netty,
      "io.netty" % "netty-codec-socks"                     % Versions.netty,
      "io.netty" % "netty-handler"                         % Versions.netty,
      "io.netty" % "netty-handler-proxy"                   % Versions.netty,
      "io.netty" % "netty-transport"                       % Versions.netty,
      "io.netty" % "netty-transport-native-unix-common"    % Versions.netty,
      "io.netty" % "netty-transport-classes-epoll"         % Versions.netty,
      "io.netty" % "netty-transport-classes-kqueue"        % Versions.netty,
      "io.netty" % "netty-resolver"                        % Versions.netty,
      "io.netty" % "netty-resolver-dns"                    % Versions.netty,
      "io.netty" % "netty-resolver-dns-classes-macos"      % Versions.netty,
      "io.netty" % "netty-codec-dns"                       % Versions.netty
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

      // DuckDB JDBC (for catalog resolver)
      Dependencies.duckdbJdbc,

      // ACL (from acl-sql)
      Dependencies.circeYaml,
      Dependencies.catsCore,

      // fs2-blobstore (multi-cloud ACL storage)
      Dependencies.blobstoreCore,
      Dependencies.blobstoreS3,
      Dependencies.blobstoreGcs,
      Dependencies.blobstoreAzure,

      // Kubernetes
      Dependencies.kubernetesClient,

      // Authentication
      Dependencies.nimbusJoseJwt,
      Dependencies.hikariCp,
      Dependencies.jbcrypt,
      Dependencies.postgresql
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
