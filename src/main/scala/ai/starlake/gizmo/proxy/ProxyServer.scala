package ai.starlake.gizmo.proxy

import ai.starlake.gizmo.proxy.config.ProxyConfig
import ai.starlake.gizmo.proxy.flight.FlightSqlProxy
import ai.starlake.gizmo.proxy.gizmoserver.GizmoServerManager
import ai.starlake.gizmo.proxy.validation.StatementValidator
import com.typesafe.scalalogging.LazyLogging
import org.apache.arrow.flight.*
import org.apache.arrow.memory.RootAllocator

import java.io.File

object ProxyServer extends LazyLogging:
  def main(args: Array[String]): Unit =
    // Parse environment variable for Gizmo server port
    val gizmoServerPort =
      sys.env.get("GIZMO_SERVER_PORT").flatMap(_.toIntOption)

    logger.info("Starting GizmoSQL Proxy Server...")

    val config = ProxyConfig.load()
    logger.info(
      s"Configuration loaded: proxy listening on ${config.proxy.host}:${config.proxy.port}"
    )
    logger.info(
      s"Backend GizmoSQL server: ${config.backend.host}:${config.backend.port}"
    )

    val validator = StatementValidator(config.validation)
    logger.info(
      s"Statement validation enabled: ${config.validation.enabled}"
    )

    // Generate INIT_SQL_COMMANDS logic
    val env = sys.env

    // Build S3 secret SQL if AWS credentials are provided
    val s3SecretSql = (env.get("AWS_KEY_ID"), env.get("AWS_SECRET"), env.get("AWS_REGION")) match
      case (Some(keyId), Some(secret), Some(region)) =>
        val scopePart = env.get("AWS_SCOPE").map(scope => s", SCOPE '$scope'").getOrElse("")
        s"""CREATE OR REPLACE PERSISTENT SECRET s3_{{SL_DB_ID}}
           |   (TYPE s3, KEY_ID '$keyId', SECRET '$secret', REGION '$region'$scopePart);""".stripMargin
      case _ => ""

    val initSqlTemplate =
      s"""CREATE OR REPLACE PERSISTENT SECRET pg_{{SL_DB_ID}}
        |   (TYPE postgres, HOST '{{PG_HOST}}',PORT {{PG_PORT}}, DATABASE {{SL_DB_ID}}, USER '{{PG_USERNAME}}',PASSWORD '{{PG_PASSWORD}}');
        |$s3SecretSql
        |CREATE OR REPLACE PERSISTENT SECRET {{SL_DB_ID}}
        |   (TYPE ducklake, METADATA_PATH '',DATA_PATH '{{SL_DATA_PATH}}', METADATA_PARAMETERS MAP {'TYPE': 'postgres', 'SECRET': 'pg_{{SL_DB_ID}}'});
        |ATTACH IF NOT EXISTS 'ducklake:{{SL_DB_ID}}' AS {{SL_DB_ID}} (READ_ONLY);
        |USE {{SL_DB_ID}};""".stripMargin

    val initSqlCommands = initSqlTemplate
      .replace("{{SL_DB_ID}}", env.getOrElse("SL_DB_ID", ""))
      .replace("{{PG_HOST}}", env.getOrElse("PG_HOST", ""))
      .replace("{{PG_PORT}}", env.getOrElse("PG_PORT", "5432"))
      .replace("{{PG_USERNAME}}", env.getOrElse("PG_USERNAME", ""))
      .replace("{{PG_PASSWORD}}", env.getOrElse("PG_PASSWORD", ""))
      .replace("{{SL_DATA_PATH}}", env.getOrElse("SL_DATA_PATH", ""))

    // Get idle timeout from EnvVars
    import ai.starlake.gizmo.ondemand.EnvVars
    val idleTimeout = EnvVars.idleTimeout
    logger.info(s"Idle timeout configuration: $idleTimeout seconds")

    // Start Gizmo server if port is provided via command line
    val gizmoServerManager: GizmoServerManager | Null = gizmoServerPort match
      case Some(port) =>
        val manager = GizmoServerManager(port, initSqlCommands, sys.env, idleTimeout)
        logger.info(s"Gizmo server port specified: $port")
        manager.start()
        manager
      case None =>
        logger.info(
          "No Gizmo server port specified, skipping Gizmo server startup"
        )
        null

    // Register shutdown hook to stop Gizmo server even on aggressive shutdown
    if gizmoServerPort.isDefined && gizmoServerManager != null then
      Runtime.getRuntime.addShutdownHook(
        new Thread:
          override def run(): Unit =
            logger.info("Shutdown hook triggered - stopping Gizmo server")
            gizmoServerManager.stop()
      )

    val allocator = new RootAllocator(Long.MaxValue)

    // Create proxy producer (it will manage backend connections per user)
    val proxyProducer = new FlightSqlProxy(config, validator, allocator, Option(gizmoServerManager))

    // Determine if TLS should be used (only if enabled AND cert files are provided)
    val useTls = config.proxy.tls.enabled &&
      config.proxy.tls.certChain.nonEmpty &&
      config.proxy.tls.privateKey.nonEmpty

    // Create and start Flight server
    val proxyLocation =
      if useTls then Location.forGrpcTls(config.proxy.host, config.proxy.port)
      else Location.forGrpcInsecure(config.proxy.host, config.proxy.port)

    val serverBuilder0Temp = FlightServer.builder(
      allocator,
      proxyLocation,
      proxyProducer
    )

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

    val key =
      FlightServerMiddleware.Key.of[FlightSqlProxy.AuthMiddleware]("auth")
    serverBuilder0Temp.middleware(key, proxyProducer.middlewareFactory)

    val serverBuilder0 = serverBuilder0Temp

    logger.info(
      "Registered NoOpAuthHandler + middleware (mimicking C++ GizmoSQL server)"
    )

    // Configure TLS if enabled
    val serverBuilder =
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

    val flightServer = serverBuilder.build()

    flightServer.start()
    logger.info(s"GizmoSQL Proxy Server started successfully")
    logger.info(
      s"Listening on ${config.proxy.host}:${config.proxy.port} (TLS: $useTls)"
    )
    logger.info(
      s"Forwarding to ${config.backend.host}:${config.backend.port} (TLS: ${config.backend.tls.enabled})"
    )
    logger.info("Press Ctrl+C to stop")

    // Wait for server to terminate
    flightServer.awaitTermination()

    // Cleanup
    // Close all backend clients
    proxyProducer.backendClients.values().forEach(_.close())
    proxyProducer.backendClients.clear()

    // Stop Gizmo server if it was started
    if gizmoServerPort.isDefined && gizmoServerManager != null then
      gizmoServerManager.stop()

    allocator.close()
    logger.info("GizmoSQL Proxy Server stopped")
