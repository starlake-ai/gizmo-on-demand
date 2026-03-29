package ai.starlake.gizmo.proxy.validation

import ai.starlake.acl.api.{AclSql, AclSqlConfig, SqlContext}
import ai.starlake.acl.model.{TenantId, UserIdentity}
import ai.starlake.acl.watcher.{TenantListener, TenantWatcher, WatcherConfig}
import ai.starlake.gizmo.proxy.catalog.DuckLakeCatalogResolver
import ai.starlake.gizmo.proxy.config.{AclConfig, SessionConfig}
import com.typesafe.scalalogging.LazyLogging

import java.nio.file.Paths
import scala.jdk.CollectionConverters.*

class AclStatementValidator(aclConfig: AclConfig, sessionConfig: SessionConfig)
    extends StatementValidator,
      AutoCloseable,
      LazyLogging:

  private val (aclSql, tenantWatcher, catalogResolver) = initAclSql()

  private def initAclSql(): (AclSql, Option[TenantWatcher], DuckLakeCatalogResolver) =
    val path = Paths.get(aclConfig.basePath)
    val resolver = new DuckLakeCatalogResolver(sessionConfig)
    val viewResolver = (tenant: TenantId, ref: ai.starlake.acl.model.TableRef) =>
      resolver.resolve(tenant, ref)
    val aclSqlConfig = AclSqlConfig(maxTenants = Some(aclConfig.maxTenants))
    val api = new AclSql(path, viewResolver, aclSqlConfig)

    if aclConfig.watcher.enabled then
      val watcherConfig = WatcherConfig(
        debounceMs = aclConfig.watcher.debounceMs,
        maxBackoffMs = aclConfig.watcher.maxBackoffMs
      )
      val listener = new TenantListener:
        override def onInvalidate(tenantId: TenantId): Unit =
          logger.info(s"ACL grants reloaded for tenant: $tenantId")
          api.invalidateTenant(tenantId)
        override def onNewTenant(tenantId: TenantId): Unit =
          logger.info(s"New ACL tenant detected: $tenantId")
        override def onTenantDeleted(tenantId: TenantId): Unit =
          logger.warn(s"ACL tenant deleted: $tenantId")
          api.invalidateTenant(tenantId)
      val watcher = new TenantWatcher(path, listener, watcherConfig)
      logger.info(s"ACL file watcher started on ${aclConfig.basePath} (debounce=${aclConfig.watcher.debounceMs}ms)")
      (api, Some(watcher), resolver)
    else
      logger.info("ACL file watcher disabled")
      (api, None, resolver)

  override def close(): Unit =
    tenantWatcher.foreach { w =>
      logger.info("Stopping ACL file watcher")
      w.close()
    }
    try catalogResolver.close()
    catch case e: Exception => logger.warn(s"Error closing catalog resolver: ${e.getMessage}")

  override def validate(context: ValidationContext): ValidationResult =
    val tenantStr = sessionConfig.aclTenant

    TenantId.parse(tenantStr) match
      case Left(err) =>
        logger.error(s"Invalid tenant ID '$tenantStr': $err")
        Denied(s"Invalid tenant: $err")
      case Right(tenantId) =>
        val groups = extractGroups(context)
        val user = UserIdentity(context.username, groups)

        val sqlContext = SqlContext(
          defaultDatabase = Some(sessionConfig.slProjectId).filter(_.nonEmpty),
          dialect = aclConfig.dialect
        )

        logger.info(
          s"ACL check: tenant=$tenantStr, user=${context.username}, groups=${groups.mkString(",")}, database=${sessionConfig.slProjectId}"
        )

        aclSql.checkAccess(tenantId, context.statement, user, trace = false, sqlContext) match
          case Right(outcome) if outcome.isAllowed =>
            logger.info(s"ACL ALLOWED: ${outcome.summary}")
            Allowed
          case Right(outcome) =>
            val reason = outcome.summary
            logger.warn(s"ACL DENIED: $reason")
            Denied(reason)
          case Left(error) =>
            logger.error(s"ACL error: ${error.message}")
            Denied(s"Authorization error: ${error.message}")

  private def extractGroups(context: ValidationContext): Set[String] =
    context.claims.get(aclConfig.groupsClaim) match
      case Some(claim) =>
        try
          Option(claim.asList(classOf[String]))
            .map(_.asScala.toSet)
            .getOrElse(Set.empty)
        catch
          case _: Exception =>
            Option(claim.asString())
              .map(_.split(",").map(_.trim).toSet)
              .getOrElse(Set.empty)
      case None =>
        // Fallback: use the "role" claim if present
        context.claims
          .get("role")
          .flatMap(c => Option(c.asString()))
          .map(Set(_))
          .getOrElse(Set.empty)
