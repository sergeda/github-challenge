import cats.Applicative
import cats.effect.{ContextShift, IO}
import cats.implicits._
import io.circe.syntax._
import org.http4s.circe._
import org.http4s.util.CaseInsensitiveString
import org.http4s.{EntityDecoder, Method, Request, Response, Status, Uri}
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import serhii.http.HttpApi
import serhii.model._
import serhii.service.GitHubClient
import serhii.utils.Errors.{RateLimited, UnknownGitHubError}

import scala.concurrent.ExecutionContext.Implicits.global

class Http4sRoutesSpec extends AnyWordSpec with Matchers with MockFactory {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(global)
  val client = stub[GitHubClient[IO]]

  val server = new HttpApi(client)

  "Contributors route " must {
    "allow to get list of contributors by name of organization" in {
      val repos = Vector(
        GitHubRepository("https://api.github.com/repos/repo/repo.rb/contributors"),
        GitHubRepository("https://api.github.com/repos/repo2/repo2.rb/contributors")
      )
      val contributors = Vector(Contributor("contr1", 10), Contributor("contr2", 20))

      (client.getRepos(_: String)).when(*) returns IO.pure(repos)
      (client.getContributors(_: Vector[GitHubRepository])).when(*).returns(IO.pure(contributors))

      val result = server.httpApp.run(Request(method = Method.GET, uri = Uri.uri("org/test/contributors")))
      assert(check(result, Status.Ok, Some(contributors.asJson)))
    }

    "return ServiceUnavailable error if rate limited by GitHub specifying Retry-After header" in {
      val retry = java.time.Instant.now().getEpochSecond + 60
      (client.getRepos(_: String)).when(*) returns IO.raiseError(RateLimited(retry))
      (client.getContributors(_: Vector[GitHubRepository])).when(*).returns(IO.raiseError(RateLimited(retry)))

      val result = server.httpApp.run(Request(method = Method.GET, uri = Uri.uri("org/test/contributors")))
      assert(check(result, Status.ServiceUnavailable, None, Some("Retry-After"), Some((_: String) => true)))
    }

    "return InternalServerError error if unknown error happened during call to GitHub" in {
      val retry = java.time.Instant.now().getEpochSecond + 60
      (client.getRepos(_: String)).when(*) returns IO.raiseError(UnknownGitHubError("error"))
      (client.getContributors(_: Vector[GitHubRepository])).when(*).returns(IO.raiseError(UnknownGitHubError("error")))

      val result = server.httpApp.run(Request(method = Method.GET, uri = Uri.uri("org/test/contributors")))
      assert(check(result, Status.InternalServerError, None))
    }

    "return 404 error if call to none-existing page" in {
      val result = server.httpApp.run(Request(method = Method.GET, uri = Uri.uri("none-existing-url")))
      assert(check(result, Status.NotFound, Some("Not found")))
    }
  }

  def check[A](
               actual: IO[Response[IO]],
               expectedStatus: Status,
               expectedBody: Option[A],
               header: Option[String] = None,
               headerCheckFunction: Option[String => Boolean] = None
  )(implicit ev: EntityDecoder[IO, A]): Boolean = {
    val actualResp = actual.unsafeRunSync()
    val statusCheck = actualResp.status == expectedStatus
    val headerCheck = Applicative[Option]
      .map2(header, headerCheckFunction)((head, check) => {
        actualResp.headers.get(CaseInsensitiveString(head)).fold(false)(h => check(h.value))
      })
      .getOrElse(true)

    val bodyCheck =
      expectedBody.fold[Boolean](actualResp.body.compile.toVector.unsafeRunSync().isEmpty)(expected => actualResp.as[A].unsafeRunSync() == expected)
    statusCheck && bodyCheck && headerCheck
  }
}
