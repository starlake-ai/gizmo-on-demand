package ai.starlake.gizmo.proxy.flight

import ai.starlake.gizmo.proxy.config.GizmoSqlProxyConfig
import ai.starlake.gizmo.proxy.gizmoserver.GizmoServerManager
import ai.starlake.gizmo.proxy.validation.{
  Allowed,
  Denied,
  StatementValidator,
  ValidationContext
}
import cats.effect.unsafe.implicits.global
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.typesafe.scalalogging.LazyLogging
import org.apache.arrow.flight.*
import org.apache.arrow.flight.auth.ServerAuthHandler
import org.apache.arrow.flight.sql.NoOpFlightSqlProducer
import org.apache.arrow.memory.RootAllocator

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.{Date, UUID}
import scala.jdk.CollectionConverters.*

class FlightSqlProxy(
    config: GizmoSqlProxyConfig,
    validator: StatementValidator,
    allocator: RootAllocator,
    gizmoServerManager: Option[GizmoServerManager]
) extends NoOpFlightSqlProducer,
      LazyLogging:

  /** Record activity on the gizmo server manager (for idle timeout) */
  private def recordActivity(): Unit =
    gizmoServerManager.foreach(_.recordActivity())

  /** Notify request completion (for immediate shutdown mode) */
  private def onRequestComplete(): Unit =
    gizmoServerManager.foreach(_.onRequestComplete())

  val backendClients = new ConcurrentHashMap[String, FlightClient]()
  val handshakeCredentials = new ConcurrentHashMap[String, String]()
  val handshakeAuthHandler = new FlightSqlProxy.HandshakeAuthHandler(
    config,
    allocator,
    backendClients,
    handshakeCredentials
  )
  val middlewareFactory = new FlightSqlProxy.AuthMiddlewareFactory(
    config,
    allocator,
    backendClients,
    handshakeCredentials
  )

  private def getBackendClient(
      context: FlightProducer.CallContext
  ): FlightClient =
    val username = FlightSqlProxy.currentUsername.get()
    val clientKey =
      if username != null && username.nonEmpty then username
      else
        Option(context.peerIdentity()).filter(_.nonEmpty).getOrElse("anonymous")

    Option(backendClients.get(clientKey)).getOrElse {
      throw CallStatus.UNAUTHENTICATED
        .withDescription(
          s"No authenticated backend client found for user: $clientKey"
        )
        .toRuntimeException()
    }

  override def getStream(
      context: FlightProducer.CallContext,
      ticket: Ticket,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    recordActivity()
    try
      val stream = getBackendClient(context).getStream(ticket)
      listener.start(stream.getRoot)
      while stream.next() do listener.putNext()
      listener.completed()
    catch
      case e: Exception =>
        logger.error("Error in getStream", e)
        listener.error(e)
    finally
      onRequestComplete()

  override def listFlights(
      context: FlightProducer.CallContext,
      criteria: Criteria,
      listener: FlightProducer.StreamListener[FlightInfo]
  ): Unit =
    recordActivity()
    try
      getBackendClient(context)
        .listFlights(criteria)
        .asScala
        .foreach(listener.onNext)
      listener.onCompleted()
    catch
      case e: Exception =>
        logger.error("Error in listFlights", e)
        listener.onError(e)
    finally
      onRequestComplete()

  override def getFlightInfo(
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): FlightInfo =
    recordActivity()
    try
      val command = descriptor.getCommand
      if command != null && command.nonEmpty then
        val commandStr = new String(command, 0, Math.min(100, command.length))

        // Flight SQL metadata commands - forward without validation
        if commandStr.contains("CommandGetTables") || commandStr.contains(
            "CommandGetCatalogs"
          ) ||
          commandStr.contains("CommandGetSchemas") || commandStr.contains(
            "CommandGetSqlInfo"
          )
        then return getBackendClient(context).getInfo(descriptor)

        // SQL statements - validate if enabled
        val fullCommandStr = new String(command)
        val normalizedStatement = stripCommentsAndWhitespace(fullCommandStr)

        if normalizedStatement.matches(
            "(?i)^(WITH|SELECT|INSERT|UPDATE|DELETE|CREATE|DROP|ALTER|TRUNCATE|EXPLAIN|DESCRIBE|SHOW|SET|USE|GRANT|REVOKE|MERGE|COPY).*"
          )
        then
          validator
            .validate(
              ValidationContext(
                username = extractUsername(context),
                database = "default",
                statement = fullCommandStr,
                peer = context.peerIdentity()
              )
            )
            .unsafeRunSync() match
            case Denied(reason) =>
              throw CallStatus.UNAUTHENTICATED
                .withDescription(s"Statement execution denied: $reason")
                .toRuntimeException()
            case Allowed => // Continue

      getBackendClient(context).getInfo(descriptor)
    catch
      case e: Exception =>
        logger.error(s"Error in getFlightInfo: ${e.getMessage}", e)
        throw e
    finally
      onRequestComplete()

  override def acceptPut(
      context: FlightProducer.CallContext,
      flightStream: FlightStream,
      ackStream: FlightProducer.StreamListener[PutResult]
  ): Runnable =
    recordActivity()
    val client = getBackendClient(context)
    val proxy = this
    new Runnable:
      override def run(): Unit =
        try
          val putStream = client.startPut(
            flightStream.getDescriptor,
            flightStream.getRoot,
            new AsyncPutListener:
              override def onNext(putResult: PutResult): Unit =
                ackStream.onNext(putResult)
          )
          while flightStream.next() do putStream.putNext()
          putStream.completed()
          ackStream.onCompleted()
        catch
          case e: Exception =>
            logger.error("Error in acceptPut", e)
            ackStream.onError(e)
        finally
          proxy.onRequestComplete()

  override def listActions(
      context: FlightProducer.CallContext,
      listener: FlightProducer.StreamListener[ActionType]
  ): Unit =
    recordActivity()
    try
      getBackendClient(context).listActions().asScala.foreach(listener.onNext)
      listener.onCompleted()
    catch
      case e: Exception =>
        logger.error("Error in listActions", e)
        listener.onError(e)
    finally
      onRequestComplete()

  override def doAction(
      context: FlightProducer.CallContext,
      action: Action,
      listener: FlightProducer.StreamListener[Result]
  ): Unit =
    recordActivity()
    try
      getBackendClient(context)
        .doAction(action)
        .asScala
        .foreach(listener.onNext)
      listener.onCompleted()
    catch
      case e: Exception =>
        logger.error("Error in doAction", e)
        listener.onError(e)
    finally
      onRequestComplete()

  override def getSchema(
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): SchemaResult =
    recordActivity()
    try
      getBackendClient(context).getSchema(descriptor)
    finally
      onRequestComplete()

  private def extractUsername(context: FlightProducer.CallContext): String =
    Option(context.peerIdentity()).filter(_.nonEmpty).getOrElse("unknown")

  private def stripCommentsAndWhitespace(sql: String): String =
    sql.replaceAll("/\\*.*?\\*/", " ").replaceAll("--[^\n\r]*", " ").trim

object FlightSqlProxy:

  // ThreadLocal to pass username from middleware to producer methods and auth handler
  // This is necessary because NoOpAuthHandler doesn't set peer identity
  // Made accessible to proxy package so ProxyServer can use it in auth handler
  private[proxy] val currentUsername = new ThreadLocal[String]()

  private def createBackendClientWithAuth(
      config: GizmoSqlProxyConfig,
      allocator: RootAllocator,
      clientKey: String,
      authHeader: String,
      logger: com.typesafe.scalalogging.Logger
  ): FlightClient =
    val backendLocation =
      if config.backend.tls.enabled then
        Location.forGrpcTls(config.backend.host, config.backend.port)
      else Location.forGrpcInsecure(config.backend.host, config.backend.port)

    FlightClient
      .builder(allocator, backendLocation)
      .intercept(
        new FlightClientMiddleware.Factory:
          override def onCallStarted(info: CallInfo): FlightClientMiddleware =
            new FlightClientMiddleware:
              override def onBeforeSendingHeaders(
                  outgoingHeaders: CallHeaders
              ): Unit =
                outgoingHeaders.insert("authorization", authHeader)
              override def onHeadersReceived(
                  incomingHeaders: CallHeaders
              ): Unit = ()
              override def onCallCompleted(status: CallStatus): Unit = ()
      )
      .build()

  class HandshakeAuthHandler(
      config: GizmoSqlProxyConfig,
      allocator: RootAllocator,
      backendClients: ConcurrentHashMap[String, FlightClient],
      handshakeCredentials: ConcurrentHashMap[String, String]
  ) extends ServerAuthHandler
      with LazyLogging:

    override def authenticate(
        outgoing: ServerAuthHandler.ServerAuthSender,
        incoming: java.util.Iterator[Array[Byte]]
    ): Boolean =
      if !incoming.hasNext || incoming.next().isEmpty then
        outgoing.send(Array.emptyByteArray)
        return true

      val handshakeRequest = new String(incoming.next(), StandardCharsets.UTF_8)
      try
        val parts = handshakeRequest.split("\u0000")
        if parts.length < 2 then
          throw new IllegalArgumentException("Invalid handshake format")

        var username = parts(0)
        var password = parts(1)

        // Handle ODBC driver format: username};PWD={password (mimicking C++ GizmoSQL server)
        if username.contains("};PWD={") then
          val delimiter = "};PWD={"
          val pos = username.indexOf(delimiter)
          password = username.substring(pos + delimiter.length)
          username = username.substring(0, pos)

        val authHeader =
          s"Basic ${java.util.Base64.getEncoder.encodeToString(s"$username:$password".getBytes(StandardCharsets.UTF_8))}"

        backendClients.put(
          username,
          createBackendClientWithAuth(
            config,
            allocator,
            username,
            authHeader,
            logger
          )
        )
        handshakeCredentials.put(username, authHeader)
        outgoing.send(username.getBytes(StandardCharsets.UTF_8))
        true
      catch
        case e: Exception =>
          throw CallStatus.UNAUTHENTICATED
            .withDescription(
              s"Handshake authentication failed: ${e.getMessage}"
            )
            .withCause(e)
            .toRuntimeException()

    override def isValid(token: Array[Byte]): java.util.Optional[String] =
      if token == null || token.isEmpty then
        if !handshakeCredentials.isEmpty then
          java.util.Optional.of(handshakeCredentials.keys().asScala.toSeq.head)
        else java.util.Optional.empty()
      else
        val username = new String(token, StandardCharsets.UTF_8)
        if handshakeCredentials.containsKey(username) then
          java.util.Optional.of(username)
        else java.util.Optional.empty()

  class AuthMiddleware(username: String, jwtSecretKey: String)
      extends FlightServerMiddleware
      with LazyLogging:
    override def onBeforeSendingHeaders(outgoingHeaders: CallHeaders): Unit =
      outgoingHeaders.insert(
        "authorization",
        s"Bearer ${AuthMiddleware.createJWTToken(username, jwtSecretKey)}"
      )

    override def onCallCompleted(status: CallStatus): Unit =
      FlightSqlProxy.currentUsername.remove()
    override def onCallErrored(err: Throwable): Unit =
      FlightSqlProxy.currentUsername.remove()

  object AuthMiddleware:
    def createJWTToken(username: String, jwtSecretKey: String): String =
      val now = Instant.now()
      JWT
        .create()
        .withIssuer("gizmosql")
        .withJWTId(s"gizmosql-server-${UUID.randomUUID()}")
        .withIssuedAt(Date.from(now))
        .withExpiresAt(Date.from(now.plusSeconds(3600)))
        .withClaim("sub", username)
        .withClaim("role", "admin")
        .withClaim("auth_method", "Basic")
        .withClaim("session_id", UUID.randomUUID().toString)
        .sign(Algorithm.HMAC256(jwtSecretKey))

  class AuthMiddlewareFactory(
      config: GizmoSqlProxyConfig,
      allocator: RootAllocator,
      backendClients: ConcurrentHashMap[String, FlightClient],
      handshakeCredentials: ConcurrentHashMap[String, String]
  ) extends FlightServerMiddleware.Factory[AuthMiddleware]
      with LazyLogging:

    override def onCallStarted(
        info: CallInfo,
        incomingHeaders: CallHeaders,
        context: RequestContext
    ): AuthMiddleware =
      val authHeader = Option(incomingHeaders.get("authorization")).getOrElse {
        throw CallStatus.UNAUTHENTICATED
          .withDescription("No Authorization header found!")
          .toRuntimeException()
      }

      try
        if authHeader.startsWith("Basic ") then
          var Array(username, password) = new String(
            java.util.Base64.getDecoder.decode(authHeader.substring(6))
          ).split(":", 2)

          // Handle ODBC driver format: username};PWD={password (mimicking C++ GizmoSQL server)
          if username.contains("};PWD={") then
            val delimiter = "};PWD={"
            val pos = username.indexOf(delimiter)
            password = username.substring(pos + delimiter.length)
            username = username.substring(0, pos)

          val jwtToken =
            AuthMiddleware.createJWTToken(username, config.session.jwtSecretKey)
          val bearerAuthHeader = s"Bearer $jwtToken"

          backendClients.put(
            username,
            createBackendClientWithAuth(
              config,
              allocator,
              username,
              bearerAuthHeader,
              logger
            )
          )
          FlightSqlProxy.currentUsername.set(username)
          new AuthMiddleware(username, config.session.jwtSecretKey)
        else if authHeader.startsWith("Bearer ") then
          val decodedJWT = JWT
            .require(Algorithm.HMAC256(config.session.jwtSecretKey))
            .withIssuer("gizmosql")
            .build()
            .verify(authHeader.substring(7))
          val username = decodedJWT.getClaim("sub").asString()

          if !backendClients.containsKey(username) then
            backendClients.put(
              username,
              createBackendClientWithAuth(
                config,
                allocator,
                username,
                authHeader,
                logger
              )
            )

          FlightSqlProxy.currentUsername.set(username)
          new AuthMiddleware(username, config.session.jwtSecretKey)
        else
          throw CallStatus.UNAUTHENTICATED
            .withDescription("Invalid Authorization Header type!")
            .toRuntimeException()
      catch
        case e: FlightRuntimeException => throw e
        case e: Exception              =>
          throw CallStatus.UNAUTHENTICATED
            .withDescription(s"Authentication failed: ${e.getMessage}")
            .withCause(e)
            .toRuntimeException()
