package ai.starlake.acl.policy

import ai.starlake.acl.AclError
import ai.starlake.acl.model.{AclPolicy, TenantId}
import cats.data.ValidatedNel
import cats.syntax.all.*

import java.nio.file.{Files, Path}
import scala.io.Source
import scala.jdk.StreamConverters.*
import scala.util.Using

/** Folder-based ACL loader for multi-tenant deployments.
  *
  * Each tenant has its own folder containing YAML ACL files. The loader
  * validates folder existence, reads all YAML files, and delegates to
  * AclLoader for parsing and merging. Empty folders represent valid tenants
  * with deny-all policy.
  */
object TenantLoader:

  /** Load ACL policy for a tenant using system environment variables.
    *
    * @param basePath
    *   Base directory containing tenant folders
    * @param tenantId
    *   Validated tenant identifier
    * @return
    *   Validated policy or accumulated errors
    */
  def load(basePath: Path, tenantId: TenantId): ValidatedNel[AclError, AclPolicy] =
    loadWithEnv(basePath, tenantId, name => Option(System.getenv(name)))

  /** Load ACL policy for a tenant with a custom environment resolver.
    *
    * @param basePath
    *   Base directory containing tenant folders
    * @param tenantId
    *   Validated tenant identifier
    * @param env
    *   Function to resolve environment variables
    * @return
    *   Validated policy or accumulated errors
    */
  def loadWithEnv(
      basePath: Path,
      tenantId: TenantId,
      env: String => Option[String]
  ): ValidatedNel[AclError, AclPolicy] =
    val tenantDir = basePath.resolve(tenantId.canonical)

    if !Files.isDirectory(tenantDir) then AclError.TenantNotFound(tenantId.canonical).invalidNel
    else
      val yamlFiles = listYamlFiles(tenantDir)
      if yamlFiles.isEmpty then
        // Empty folder is valid - tenant exists with no grants (deny all)
        AclPolicy(List.empty, ResolutionMode.Strict).validNel
      else
        val yamlContents = yamlFiles.map(readFile)
        AclLoader.loadAllWithEnv(yamlContents, env)

  /** List YAML files in a directory, sorted for deterministic processing. */
  private def listYamlFiles(dir: Path): List[Path] =
    Using.resource(Files.list(dir)) { stream =>
      stream
        .toScala(List)
        .filter(Files.isRegularFile(_))
        .filter { p =>
          val name = p.getFileName.toString.toLowerCase
          name.endsWith(".yaml") || name.endsWith(".yml")
        }
        .sorted
    }

  /** Read file contents as a string. */
  private def readFile(path: Path): String =
    Using.resource(Source.fromFile(path.toFile))(_.mkString)
