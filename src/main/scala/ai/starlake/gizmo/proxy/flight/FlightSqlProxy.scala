package ai.starlake.gizmo.proxy.flight

import ai.starlake.gizmo.proxy.config.GizmoSqlProxyConfig
import ai.starlake.gizmo.proxy.gizmoserver.GizmoServerManager
import ai.starlake.gizmo.proxy.validation.{
  Allowed,
  Denied,
  StatementValidator,
  ValidationContext
}

import ai.starlake.gizmo.proxy.auth.AuthenticationService
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.Claim
import com.google.protobuf.Any as ProtobufAny
import com.typesafe.scalalogging.LazyLogging
import org.apache.arrow.flight.*
import org.apache.arrow.flight.auth.ServerAuthHandler
import org.apache.arrow.flight.sql.NoOpFlightSqlProducer
import org.apache.arrow.flight.sql.impl.FlightSql.{ActionCreatePreparedStatementRequest, CommandStatementQuery}
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
    gizmoServerManager: Option[GizmoServerManager],
    authService: Option[AuthenticationService] = None
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
    handshakeCredentials,
    authService
  )
  val middlewareFactory = new FlightSqlProxy.AuthMiddlewareFactory(
    config,
    allocator,
    backendClients,
    handshakeCredentials,
    authService
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
      case e: FlightRuntimeException if e.status().code() == FlightStatusCode.CANCELLED =>
        logger.debug("Client cancelled stream", e)
      case e: Exception =>
        logger.error("Error in getStream", e)
        try listener.error(e)
        catch case _: Exception => () // listener already closed
    finally onRequestComplete()

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
    finally onRequestComplete()

  override def getFlightInfo(
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): FlightInfo =
    recordActivity()
    try
      val command = descriptor.getCommand
      if command != null && command.nonEmpty then
        // Deserialize protobuf to extract the actual SQL statement
        try
          val any = ProtobufAny.parseFrom(command)
          if any.is(classOf[CommandStatementQuery]) then
            validateSql(context, any.unpack(classOf[CommandStatementQuery]).getQuery)
          // else: Metadata command (CommandGetTables, CommandGetCatalogs, etc.) - no validation needed
        catch
          case e: FlightRuntimeException => throw e // Re-throw validation denials
          case e: Exception =>
            // Fail-closed: deny unparseable commands rather than skipping validation
            logger.warn(s"Could not parse command as protobuf, denying by default: ${e.getMessage}")
            throw CallStatus.UNAUTHENTICATED
              .withDescription("Unable to parse command for validation — denied by default")
              .toRuntimeException()

      getBackendClient(context).getInfo(descriptor)
    catch
      case e: Exception =>
        logger.error(s"Error in getFlightInfo: ${e.getMessage}", e)
        throw e
    finally onRequestComplete()

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
        finally proxy.onRequestComplete()

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
    finally onRequestComplete()

  override def doAction(
      context: FlightProducer.CallContext,
      action: Action,
      listener: FlightProducer.StreamListener[Result]
  ): Unit =
    recordActivity()
    try
      // Intercept CreatePreparedStatement to validate SQL before forwarding
      if action.getType == "CreatePreparedStatement" then
        val body = action.getBody
        if body != null && body.nonEmpty then
          try
            val any = ProtobufAny.parseFrom(body)
            if any.is(classOf[ActionCreatePreparedStatementRequest]) then
              validateSql(context, any.unpack(classOf[ActionCreatePreparedStatementRequest]).getQuery)
          catch
            case e: FlightRuntimeException => throw e
            case e: Exception =>
              // Fail-closed: deny unparseable prepared statements
              logger.warn(s"Could not parse CreatePreparedStatement body, denying by default: ${e.getMessage}")
              throw CallStatus.UNAUTHENTICATED
                .withDescription("Unable to parse prepared statement for validation — denied by default")
                .toRuntimeException()

      getBackendClient(context)
        .doAction(action)
        .asScala
        .foreach(listener.onNext)
      listener.onCompleted()
    catch
      case e: FlightRuntimeException => throw e // Propagate validation denials as gRPC status
      case e: Exception =>
        logger.error("Error in doAction", e)
        listener.onError(e)
    finally onRequestComplete()

  override def getSchema(
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): SchemaResult =
    recordActivity()
    try
      getBackendClient(context).getSchema(descriptor)
    finally
      onRequestComplete()

  /** Validate a SQL statement against the configured validator.
    * Throws FlightRuntimeException with UNAUTHENTICATED status if denied.
    */
  private def validateSql(context: FlightProducer.CallContext, sql: String): Unit =
    logger.debug(s"Validating SQL: ${stripCommentsAndWhitespace(sql)}")
    validator.validate(
      ValidationContext(
        username = extractUsername(context),
        database = config.session.slProjectId,
        statement = sql,
        peer = context.peerIdentity(),
        claims = Option(FlightSqlProxy.currentClaims.get()).getOrElse(Map.empty)
      )
    ) match
      case Denied(reason) =>
        throw CallStatus.UNAUTHENTICATED
          .withDescription(s"Statement execution denied: $reason")
          .toRuntimeException()
      case Allowed => // Continue

  private def extractUsername(context: FlightProducer.CallContext): String =
    Option(context.peerIdentity()).filter(_.nonEmpty).getOrElse("unknown")

  private def stripCommentsAndWhitespace(sql: String): String =
    sql.replaceAll("/\\*.*?\\*/", " ").replaceAll("--[^\n\r]*", " ").trim

object FlightSqlProxy:

  // ThreadLocal to pass username from middleware to producer methods and auth handler
  // This is necessary because NoOpAuthHandler doesn't set peer identity
  // Made accessible to proxy package so ProxyServer can use it in auth handler
  private[proxy] val currentUsername = new ThreadLocal[String]()
  private[proxy] val currentClaims = new ThreadLocal[Map[String, Claim]]()

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
      handshakeCredentials: ConcurrentHashMap[String, String],
      authService: Option[AuthenticationService]
  ) extends ServerAuthHandler
      with LazyLogging:

    override def authenticate(
        outgoing: ServerAuthHandler.ServerAuthSender,
        incoming: java.util.Iterator[Array[Byte]]
    ): Boolean =
      if !incoming.hasNext then
        outgoing.send(Array.emptyByteArray)
        return true

      val firstToken = incoming.next()
      if firstToken.isEmpty then
        outgoing.send(Array.emptyByteArray)
        return true

      val handshakeRequest = new String(firstToken, StandardCharsets.UTF_8)
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

        // Discovery handshake: client sends username="__discover__" to learn the OAuth URL
        if username == "__discover__" then
          authService.flatMap(_.oauthBaseUrl) match
            case Some(oauthUrl) =>
              logger.info(s"Discovery handshake: returning OAuth URL $oauthUrl")
              // Return OAuth URL as Bearer token JSON (mimics C++ GizmoSQL DiscoveryMiddleware)
              val jsonPayload = s"""{"oauth_url":"$oauthUrl"}"""
              outgoing.send(jsonPayload.getBytes(StandardCharsets.UTF_8))
              return true
            case None =>
              throw new IllegalArgumentException("OAuth is not enabled on this server")

        // Token-based auth: username="token", password=<external JWT/ID token>
        // (used by ADBC Python driver after OAuth browser flow)
        val (authenticatedUsername, role, groups, authMethod) =
          if username == "token" then
            authService match
              case Some(service) =>
                service.authenticateBearer(password) match
                  case Right(profile) =>
                    (profile.username, profile.role, profile.groups.toList, "BootstrapToken")
                  case Left(error) =>
                    throw new IllegalArgumentException(s"Token authentication failed: $error")
              case None =>
                throw new IllegalArgumentException("No auth providers configured for token validation")
          else
            // Regular username/password auth
            val (r, g) = authService match
              case Some(service) =>
                service.authenticateBasic(username, password) match
                  case Right(profile) => (profile.role, profile.groups.toList)
                  case Left(error) =>
                    throw new IllegalArgumentException(s"Authentication failed: $error")
              case None => ("admin", Nil)
            (username, r, g, "Basic")

        val jwtToken = AuthMiddleware.createJWTToken(
          authenticatedUsername, config.session.jwtSecretKey, role, groups, authMethod
        )
        val bearerAuthHeader = s"Bearer $jwtToken"

        backendClients.put(
          authenticatedUsername,
          createBackendClientWithAuth(
            config,
            allocator,
            authenticatedUsername,
            bearerAuthHeader,
            logger
          )
        )
        handshakeCredentials.put(authenticatedUsername, bearerAuthHeader)
        outgoing.send(authenticatedUsername.getBytes(StandardCharsets.UTF_8))
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

  class AuthMiddleware(username: String, jwtSecretKey: String, oauthUrl: Option[String] = None)
      extends FlightServerMiddleware
      with LazyLogging:
    override def onBeforeSendingHeaders(outgoingHeaders: CallHeaders): Unit =
      oauthUrl match
        case Some(url) =>
          // Discovery response: return OAuth URL in headers (mimics C++ DiscoveryMiddleware)
          val jsonPayload = s"""{"oauth_url":"$url"}"""
          outgoingHeaders.insert("authorization", s"Bearer $jsonPayload")
          outgoingHeaders.insert("x-gizmosql-oauth-url", url)
        case None =>
          outgoingHeaders.insert(
            "authorization",
            s"Bearer ${AuthMiddleware.createJWTToken(username, jwtSecretKey)}"
          )

    override def onCallCompleted(status: CallStatus): Unit =
      FlightSqlProxy.currentUsername.remove()
      FlightSqlProxy.currentClaims.remove()
    override def onCallErrored(err: Throwable): Unit =
      FlightSqlProxy.currentUsername.remove()
      FlightSqlProxy.currentClaims.remove()

  object AuthMiddleware:
    def createJWTToken(
        username: String,
        jwtSecretKey: String,
        role: String = "admin",
        groups: List[String] = Nil,
        authMethod: String = "Basic"
    ): String =
      val now = Instant.now()
      val builder = JWT
        .create()
        .withIssuer("gizmosql")
        .withJWTId(s"gizmosql-server-${UUID.randomUUID()}")
        .withIssuedAt(Date.from(now))
        .withExpiresAt(Date.from(now.plusSeconds(3600)))
        .withClaim("sub", username)
        .withClaim("role", role)
        .withClaim("auth_method", authMethod)
        .withClaim("session_id", UUID.randomUUID().toString)
      if groups.nonEmpty then
        builder.withClaim("groups", java.util.Arrays.asList(groups*))
      builder.sign(Algorithm.HMAC256(jwtSecretKey))

  class AuthMiddlewareFactory(
      config: GizmoSqlProxyConfig,
      allocator: RootAllocator,
      backendClients: ConcurrentHashMap[String, FlightClient],
      handshakeCredentials: ConcurrentHashMap[String, String],
      authService: Option[AuthenticationService]
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
          val decoded = new String(
            java.util.Base64.getDecoder.decode(authHeader.substring(6))
          )
          val colonIndex = decoded.indexOf(':')
          if colonIndex < 0 then
            throw CallStatus.UNAUTHENTICATED
              .withDescription("Malformed Basic credentials: missing ':'")
              .toRuntimeException()
          var username = decoded.substring(0, colonIndex)
          var password = decoded.substring(colonIndex + 1)

          // Handle ODBC driver format: username};PWD={password (mimicking C++ GizmoSQL server)
          if username.contains("};PWD={") then
            val delimiter = "};PWD={"
            val pos = username.indexOf(delimiter)
            password = username.substring(pos + delimiter.length)
            username = username.substring(0, pos)

          // Discovery: username="__discover__" returns OAuth URL
          if username == "__discover__" then
            authService.flatMap(_.oauthBaseUrl) match
              case Some(oauthUrl) =>
                logger.info(s"Discovery via Basic auth: returning OAuth URL $oauthUrl")
                FlightSqlProxy.currentUsername.set("__discover__")
                return new AuthMiddleware("__discover__", config.session.jwtSecretKey, Some(oauthUrl))
              case None =>
                throw CallStatus.UNAUTHENTICATED
                  .withDescription("OAuth is not enabled on this server")
                  .toRuntimeException()

          // Token-based auth: username="token", password=<external JWT>
          val (authenticatedUsername, role, groups, authMethod) =
            if username == "token" then
              authService match
                case Some(service) =>
                  service.authenticateBearer(password) match
                    case Right(profile) =>
                      (profile.username, profile.role, profile.groups.toList, "BootstrapToken")
                    case Left(error) =>
                      throw CallStatus.UNAUTHENTICATED
                        .withDescription(s"Token authentication failed: $error")
                        .toRuntimeException()
                case None =>
                  throw CallStatus.UNAUTHENTICATED
                    .withDescription("No auth providers configured for token validation")
                    .toRuntimeException()
            else
              // Regular username/password: authenticate via configured providers
              val (r, g, m) = authService match
                case Some(service) =>
                  service.authenticateBasic(username, password) match
                    case Right(profile) =>
                      (profile.role, profile.groups.toList, profile.authMethod)
                    case Left(error) =>
                      throw CallStatus.UNAUTHENTICATED
                        .withDescription(s"Authentication failed: $error")
                        .toRuntimeException()
                case None => ("admin", Nil, "Basic")
              (username, r, g, m)

          val jwtToken = AuthMiddleware.createJWTToken(
            authenticatedUsername, config.session.jwtSecretKey, role, groups, authMethod
          )
          val bearerAuthHeader = s"Bearer $jwtToken"

          val decodedMinted = JWT.decode(jwtToken)
          FlightSqlProxy.currentClaims.set(
            decodedMinted.getClaims.asScala.toMap
          )

          backendClients.put(
            authenticatedUsername,
            createBackendClientWithAuth(
              config,
              allocator,
              authenticatedUsername,
              bearerAuthHeader,
              logger
            )
          )
          FlightSqlProxy.currentUsername.set(authenticatedUsername)
          new AuthMiddleware(authenticatedUsername, config.session.jwtSecretKey)
        else if authHeader.startsWith("Bearer ") then
          val token = authHeader.substring(7)
          // First try self-issued JWT verification (existing behavior)
          try
            val decodedJWT = JWT
              .require(Algorithm.HMAC256(config.session.jwtSecretKey))
              .withIssuer("gizmosql")
              .build()
              .verify(token)
            val username = decodedJWT.getClaim("sub").asString()
            FlightSqlProxy.currentClaims.set(decodedJWT.getClaims.asScala.toMap)

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
          catch
            case _: Exception =>
              // Not a self-issued token — try external providers
              authService match
                case Some(service) =>
                  service.authenticateBearer(token) match
                    case Right(profile) =>
                      // Mint a backend JWT with the validated identity
                      val jwtToken = AuthMiddleware.createJWTToken(
                        profile.username, config.session.jwtSecretKey,
                        profile.role, profile.groups.toList, profile.authMethod
                      )
                      val bearerAuthHeader = s"Bearer $jwtToken"

                      val decodedMinted = JWT.decode(jwtToken)
                      FlightSqlProxy.currentClaims.set(
                        decodedMinted.getClaims.asScala.toMap
                      )

                      backendClients.put(
                        profile.username,
                        createBackendClientWithAuth(
                          config,
                          allocator,
                          profile.username,
                          bearerAuthHeader,
                          logger
                        )
                      )
                      FlightSqlProxy.currentUsername.set(profile.username)
                      new AuthMiddleware(profile.username, config.session.jwtSecretKey)
                    case Left(error) =>
                      throw CallStatus.UNAUTHENTICATED
                        .withDescription(s"Bearer authentication failed: $error")
                        .toRuntimeException()
                case None =>
                  throw CallStatus.UNAUTHENTICATED
                    .withDescription("Invalid Bearer token")
                    .toRuntimeException()
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
