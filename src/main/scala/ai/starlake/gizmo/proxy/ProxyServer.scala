package ai.starlake.gizmo.proxy

import ai.starlake.gizmo.proxy.config.ProxyConfig
import ai.starlake.gizmo.proxy.flight.FlightSqlProxy
import ai.starlake.gizmo.proxy.gizmoserver.GizmoServerManager
import ai.starlake.gizmo.proxy.validation.StatementValidator
import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.typesafe.scalalogging.LazyLogging
import org.apache.arrow.flight.*
import org.apache.arrow.memory.RootAllocator

import java.io.File

object ProxyServer extends IOApp with LazyLogging:

  override def run(args: List[String]): IO[ExitCode] =
    // Parse environment variable for Gizmo server port
    val gizmoServerPort =
      sys.env.get("GIZMO_SERVER_PORT").flatMap(_.toIntOption)

    for
      _ <- IO(logger.info("Starting GizmoSQL Proxy Server..."))

      config <- IO(ProxyConfig.load())
      _ <- IO(
        logger.info(
          s"Configuration loaded: proxy listening on ${config.proxy.host}:${config.proxy.port}"
        )
      )
      _ <- IO(
        logger.info(
          s"Backend GizmoSQL server: ${config.backend.host}:${config.backend.port}"
        )
      )

      validator = StatementValidator(config.validation)
      _ <- IO(
        logger.info(
          s"Statement validation enabled: ${config.validation.enabled}"
        )
      )

      // Generate INIT_SQL_COMMANDS logic
      initSqlCommands <- IO:
        val env = sys.env
        val initSqlTemplate =
          """CREATE OR REPLACE PERSISTENT SECRET pg_{{SL_PROJECT_ID}} (TYPE postgres, HOST '{{PG_HOST}}',PORT {{PG_PORT}}, DATABASE {{SL_PROJECT_ID}}, USER '{{PG_USERNAME}}',PASSWORD '{{PG_PASSWORD}}');CREATE OR REPLACE PERSISTENT SECRET {{SL_PROJECT_ID}} (TYPE ducklake, METADATA_PATH '',DATA_PATH '{{SL_DATA_PATH}}', METADATA_PARAMETERS MAP {'TYPE': 'postgres', 'SECRET': 'pg_{{SL_PROJECT_ID}}'});ATTACH IF NOT EXISTS 'ducklake:{{SL_PROJECT_ID}}' AS {{SL_PROJECT_ID}} (READ_ONLY); USE {{SL_PROJECT_ID}};"""

        initSqlTemplate
          .replace("{{SL_PROJECT_ID}}", env.getOrElse("SL_PROJECT_ID", ""))
          .replace("{{PG_HOST}}", env.getOrElse("PG_HOST", ""))
          .replace("{{PG_PORT}}", env.getOrElse("PG_PORT", "5432"))
          .replace("{{PG_USERNAME}}", env.getOrElse("PG_USERNAME", ""))
          .replace("{{PG_PASSWORD}}", env.getOrElse("PG_PASSWORD", ""))
          .replace("{{SL_DATA_PATH}}", env.getOrElse("SL_DATA_PATH", ""))

      // Start Gizmo server if port is provided via command line
      gizmoServerManager <- gizmoServerPort match
        case Some(port) =>
          GizmoServerManager
            .resource(port, initSqlCommands, sys.env)
            .allocated
            .map(_._1)
        case None =>
          IO.pure(null)

      _ <- gizmoServerPort match
        case Some(port) =>
          logger.info(s"Gizmo server port specified: $port")
          gizmoServerManager.start()
        case None =>
          logger.info(
            "No Gizmo server port specified, skipping Gizmo server startup"
          )
          IO.unit

      // Register shutdown hook to stop Gizmo server even on aggressive shutdown
      _ <-
        if gizmoServerPort.isDefined && gizmoServerManager != null then
          IO:
            import cats.effect.unsafe.implicits.global
            Runtime.getRuntime.addShutdownHook(
              new Thread:
                override def run(): Unit =
                  logger.info("Shutdown hook triggered - stopping Gizmo server")
                  gizmoServerManager.stop().unsafeRunSync()
            )
        else IO.unit

      allocator = new RootAllocator(Long.MaxValue)

      // Create proxy producer (it will manage backend connections per user)
      proxyProducer = new FlightSqlProxy(config, validator, allocator)

      // Determine if TLS should be used (only if enabled AND cert files are provided)
      useTls = config.proxy.tls.enabled &&
        config.proxy.tls.certChain.nonEmpty &&
        config.proxy.tls.privateKey.nonEmpty

      // Create and start Flight server
      proxyLocation =
        if useTls then Location.forGrpcTls(config.proxy.host, config.proxy.port)
        else Location.forGrpcInsecure(config.proxy.host, config.proxy.port)

      serverBuilder0Temp = FlightServer.builder(
        allocator,
        proxyLocation,
        proxyProducer
      )

      _ <- IO:
        serverBuilder0Temp.authHandler(
          new org.apache.arrow.flight.auth.ServerAuthHandler:
            override def authenticate(
                outgoing: org.apache.arrow.flight.auth.ServerAuthHandler.ServerAuthSender,
                incoming: java.util.Iterator[Array[Byte]]
            ): Boolean =
              true
            override def isValid(
                token: Array[Byte]
            ): java.util.Optional[String] =
              // Get username from ThreadLocal (set by middleware)
              val username =
                ai.starlake.gizmo.proxy.flight.FlightSqlProxy.currentUsername
                  .get()
              if username != null && username.nonEmpty then
                java.util.Optional.of(username)
              else java.util.Optional.empty()
        )

      _ <- IO:
        val key =
          FlightServerMiddleware.Key.of[FlightSqlProxy.AuthMiddleware]("auth")
        serverBuilder0Temp.middleware(key, proxyProducer.middlewareFactory)

      serverBuilder0 = serverBuilder0Temp

      _ <- IO(
        logger.info(
          "Registered NoOpAuthHandler + middleware (mimicking C++ GizmoSQL server)"
        )
      )

      // Configure TLS if enabled
      serverBuilder =
        if useTls then
          logger.info(s"Enabling TLS with cert: ${config.proxy.tls.certChain}")
          serverBuilder0.useTls(
            new File(config.proxy.tls.certChain),
            new File(config.proxy.tls.privateKey)
          )
        else
          if config.proxy.tls.enabled then
            logger.warn(
              "TLS enabled but cert-chain or private-key not configured, using insecure connection"
            )
          serverBuilder0

      flightServer = serverBuilder.build()

      _ <- IO(flightServer.start())
      _ <- IO(logger.info(s"GizmoSQL Proxy Server started successfully"))
      _ <- IO(
        logger.info(
          s"Listening on ${config.proxy.host}:${config.proxy.port} (TLS: $useTls)"
        )
      )
      _ <- IO(
        logger.info(
          s"Forwarding to ${config.backend.host}:${config.backend.port} (TLS: ${config.backend.tls.enabled})"
        )
      )
      _ <- IO(logger.info("Press Ctrl+C to stop"))

      // Wait for server to terminate
      _ <- IO.blocking(flightServer.awaitTermination())

      // Cleanup
      _ <- IO:
        // Close all backend clients
        proxyProducer.backendClients.values().forEach(_.close())
        proxyProducer.backendClients.clear()

      // Stop Gizmo server if it was started
      _ <-
        if gizmoServerPort.isDefined && gizmoServerManager != null then
          gizmoServerManager.stop()
        else IO.unit

      _ <- IO(allocator.close())
      _ <- IO(logger.info("GizmoSQL Proxy Server stopped"))
    yield ExitCode.Success
