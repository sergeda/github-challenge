package serhii.utils

object Errors {
  case class RateLimited(until: Long) extends RuntimeException("Rate limited")
  case class DecodingJsonError(message: String) extends RuntimeException(message)
  case class UnknownGitHubError(message: String) extends RuntimeException(message)
}
