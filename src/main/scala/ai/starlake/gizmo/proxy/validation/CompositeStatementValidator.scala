package ai.starlake.gizmo.proxy.validation

import com.typesafe.scalalogging.LazyLogging

class CompositeStatementValidator(
    first: StatementValidator,
    second: StatementValidator
) extends StatementValidator,
      LazyLogging:

  override def validate(context: ValidationContext): ValidationResult =
    first.validate(context) match
      case Allowed => second.validate(context)
      case denied  => denied
