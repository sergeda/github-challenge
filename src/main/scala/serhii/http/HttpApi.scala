package serhii.http

import cats.effect.Sync
import org.http4s.implicits._
import org.http4s.{HttpApp, HttpRoutes}
import serhii.service.GitHubClient

final class HttpApi[F[_]: Sync](gitHub: GitHubClient[F]) {

  private val httpRoutes: HttpRoutes[F] =
    new Http4sRoutes(gitHub).gitHubRoutes

  val httpApp: HttpApp[F] = httpRoutes.orNotFound

}
