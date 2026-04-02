package ai.starlake.gizmo.proxy.auth

import ai.starlake.gizmo.proxy.config.DatabaseAuthConfig
import at.favre.lib.crypto.bcrypt.BCrypt
import com.typesafe.scalalogging.LazyLogging
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

/** Authenticates username/password against a PostgreSQL database.
  * Passwords must be stored as BCrypt hashes.
  *
  * Two modes:
  *   - Table mode (default): builds a query from usersTable/column config.
  *   - Custom query mode: uses a user-provided SQL query.
  *     The query must return two columns (password_hash, role) and
  *     use `?` as the placeholder for the username parameter.
  *     Example: `SELECT password_hash, role FROM my_view WHERE email = ?`
  */
class DatabaseAuthenticator(config: DatabaseAuthConfig, roleClaim: String)
    extends BasicAuthProvider,
      LazyLogging:

  val name = "database"

  private val dataSource: HikariDataSource =
    val hc = new HikariConfig()
    hc.setJdbcUrl(config.jdbcUrl)
    hc.setUsername(config.username)
    hc.setPassword(config.password)
    hc.setMaximumPoolSize(5)
    hc.setMinimumIdle(1)
    hc.setConnectionTimeout(5000)
    new HikariDataSource(hc)

  private val query: String =
    if config.query.nonEmpty then config.query
    else s"SELECT ${config.passwordColumn}, ${config.roleColumn} FROM ${config.usersTable} WHERE ${config.usernameColumn} = ?"

  override def authenticate(
      username: String,
      password: String
  ): Either[String, AuthenticatedProfile] =
    try
      val conn = dataSource.getConnection
      try
        val ps = conn.prepareStatement(query)
        try
          ps.setString(1, username)
          val rs = ps.executeQuery()
          if rs.next() then
            val storedHash = rs.getString(1)
            val role = Option(rs.getString(2)).getOrElse("user")
            if BCrypt.verifyer().verify(password.toCharArray, storedHash).verified then
              Right(
                AuthenticatedProfile(
                  username = username,
                  role = role,
                  groups = Set(role),
                  claims = Map("sub" -> username, "role" -> role, "auth_method" -> "database"),
                  authMethod = "database"
                )
              )
            else Left("Invalid password")
          else Left("User not found")
        finally ps.close()
      finally conn.close()
    catch
      case e: Exception =>
        logger.error(s"Database authentication error for '$username': ${e.getMessage}", e)
        Left(s"Database error: ${e.getMessage}")

  override def close(): Unit =
    dataSource.close()