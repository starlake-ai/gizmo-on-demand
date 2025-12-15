package ai.starlake.gizmo.proxy.validation

import ai.starlake.gizmo.proxy.config.ValidationConfig
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging

case class ValidationContext(
  username: String,
  database: String,
  statement: String,
  peer: String
)

sealed trait ValidationResult
case object Allowed extends ValidationResult
case class Denied(reason: String) extends ValidationResult

trait StatementValidator:
  def validate(context: ValidationContext): IO[ValidationResult]

class DefaultStatementValidator(config: ValidationConfig)
    extends StatementValidator, LazyLogging:

  override def validate(context: ValidationContext): IO[ValidationResult] =
    if !config.enabled then
      logger.debug("Validation disabled, allowing statement")
      IO.pure(Allowed)
    else if config.rules.bypassUsers.contains(context.username) then
      logger.info(s"User ${context.username} bypasses validation")
      IO.pure(Allowed)
    else
      IO {
        // Log the validation attempt
        logger.info(
          s"Validating statement for user=${context.username}, " +
            s"database=${context.database}, peer=${context.peer}"
        )
        logger.debug(s"Statement: ${context.statement}")

        // Apply validation rules
        val result = applyRules(context)

        result match
          case Allowed =>
            logger.info(s"Statement ALLOWED for user=${context.username}")
          case Denied(reason) =>
            logger.warn(s"Statement DENIED for user=${context.username}: $reason")

        result
      }

  private def applyRules(context: ValidationContext): ValidationResult =
    // This is just an example
    // We need to query Starlake statement validator here.
    val statement = context.statement.trim.toUpperCase
    logger.debug(s"Validating statement: $statement")
    if statement.startsWith("DROP DATABASE") || statement.startsWith("DROP TABLE") then
      return Denied("DROP operations are not allowed")

    if config.rules.allowByDefault then
      Allowed
    else
      if statement.startsWith("SELECT") ||
          statement.startsWith("INSERT") ||
          (statement.startsWith("UPDATE") && statement.contains("WHERE")) then
        Allowed
      else
        Denied("Statement type not explicitly allowed")

object StatementValidator:
  def apply(config: ValidationConfig): StatementValidator =
    new DefaultStatementValidator(config)
