package serhii.utils

import scala.util.control.NoStackTrace

object Errors {
  sealed trait ApplicationError extends NoStackTrace
  case class RateLimited(until: Long) extends ApplicationError
  case class DecodingJsonError(message: String) extends ApplicationError
  case class UnknownGitHubError(message: String) extends ApplicationError
}
