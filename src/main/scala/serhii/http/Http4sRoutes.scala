package serhii.http

import cats.effect.Sync
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Retry-After`
import serhii.service.GitHubClient
import serhii.utils.Errors.{RateLimited, UnknownGitHubError}

class Http4sRoutes[F[_]: Sync](gitHub: GitHubClient[F]) extends Http4sDsl[F] with LazyLogging {

  def gitHubRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "org" / organization / "contributors" =>
      val escapedOrganization = java.net.URLEncoder.encode(organization, "UTF-8")
      (for {
        repositories <- gitHub.getRepos(escapedOrganization)
        contributors <- gitHub.getContributors(repositories)
      } yield Ok(contributors)).flatten.recoverWith({
        case RateLimited(time) =>
          ServiceUnavailable(`Retry-After`.unsafeFromLong(time))
        case UnknownGitHubError(error) =>
          logger.error("Unknown error happened during calling GitHub: " + error)
          InternalServerError()
      })
  }

}
