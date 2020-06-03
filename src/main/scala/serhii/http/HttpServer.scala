package serhii.http

import cats.effect.{ConcurrentEffect, Sync, Timer}
import org.http4s.server.blaze.BlazeServerBuilder
import serhii.service.GitHubClient

class HttpServer[F[_]: Sync: ConcurrentEffect: Timer](host: String, port: Int, gitHub: GitHubClient[F]) {
  val httpApi = new HttpApi(gitHub)

  val server: F[Unit] = BlazeServerBuilder[F].bindHttp(port, host).withHttpApp(httpApi.httpApp).serve.compile.drain
}
