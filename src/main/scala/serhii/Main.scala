package serhii

import cats.effect.IO._
import cats.effect.{ExitCode, IO, IOApp}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import serhii.http.HttpServer
import serhii.service.GitHubClientImpl
import sttp.client.asynchttpclient.cats.AsyncHttpClientCatsBackend

object Main extends IOApp with LazyLogging {

  private val config = ConfigFactory.load()

  override def run(args: List[String]): IO[ExitCode] = {
    AsyncHttpClientCatsBackend.resource().use { implicit backend =>
      new HttpServer(
        config.getString("server.host"),
        config.getInt("server.port"),
        gitHub = new GitHubClientImpl[IO](
          config.getString("github.key"),
          config.getInt("client.concurrent-requests"),
          config.getInt("client.request-timeout")
        )
      ).server.redeemWith(ex => {
        logger.error(ex.getMessage)
        IO.raiseError(ex)
      }, _ => IO.pure(ExitCode.Success))
    }
  }
}
