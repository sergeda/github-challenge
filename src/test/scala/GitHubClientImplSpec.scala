import cats.effect.{ContextShift, IO}
import cats.implicits._
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{OptionValues, PrivateMethodTester}
import serhii.model.{Contributor, GitHubRepository}
import serhii.service.GitHubClientImpl
import serhii.utils.Errors.{DecodingJsonError, RateLimited}
import sttp.client.asynchttpclient.cats.AsyncHttpClientCatsBackend
import sttp.client.{NothingT, Response, SttpBackend}
import sttp.model.{Header, StatusCode}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class GitHubClientImplSpec extends AnyWordSpec with Matchers with PrivateMethodTester with OptionValues {

  implicit val contextShift: ContextShift[IO] = IO.contextShift(global)

  val page1headers = Vector(
    new Header(
      "Link",
      "<https://api.github.com/organizations/3430433/repos?page=2>; rel=\"next\", <https://api.github.com/organizations/3430433/repos?page=2>; rel=\"last\""
    )
  )

  val config = ConfigFactory.load()

  val token = config.getString("github.key")
  val concurrency =config.getInt("client.concurrent-requests")
  val timeout =config.getInt("client.request-timeout")


  val rateLimitedHeaders =
    Vector(new Header("X-RateLimit-Remaining", "0"), new Header("X-RateLimit-Reset", (java.time.Instant.now().getEpochSecond + 2).toString))

  val page1RepositoryResponse = Response(
    config.getString("repository_list_body").asRight[String],
    StatusCode.Ok,
    StatusCode.Ok.toString(),
    page1headers,
    List.empty[Response[Unit]]
  )

  val page2RepositoryResponse = Response(
    config.getString("repository_list_body").asRight[String],
    StatusCode.Ok,
    StatusCode.Ok.toString(),
    Vector.empty[Header],
    List.empty[Response[Unit]]
  )

  val rateLimitedRepositoryResponse =
    Response("".asRight[String], StatusCode.Forbidden, StatusCode.Forbidden.toString(), rateLimitedHeaders, List.empty[Response[Unit]])
  implicit val backend: SttpBackend[IO, Nothing, NothingT] = AsyncHttpClientCatsBackend.stub[IO].whenRequestMatchesPartial {
    case r if r.uri.path.toVector.contains("test_rate") =>
      rateLimitedRepositoryResponse
    case r if r.uri.path.endsWith(List("repos")) =>
      page1RepositoryResponse
    case r if r.uri.path.endsWith(List("?page=2")) =>
      page2RepositoryResponse
  }
  val resetLimitedStatus = PrivateMethod[Option[Long]]('getRateLimitResetStatus)
  val rateLimitSetter = PrivateMethod[Option[Long]]('setRateLimitResetStatus)

  "GitHubImpl" must {
    "return None if it's not limited" in {
      val gitHub = new GitHubClientImpl[IO](token, concurrency, timeout)
      (gitHub invokePrivate resetLimitedStatus()) shouldBe empty
    }

    "return value if it's limited and time haven't passed" in {
      val gitHub = new GitHubClientImpl[IO](token, concurrency, timeout)
      //sets time to the future
      gitHub invokePrivate rateLimitSetter(Some(System.currentTimeMillis() + 10000))

      (gitHub invokePrivate resetLimitedStatus()) shouldBe defined
    }

    "correctly parse rate limited error" in {
      val gitHub = new GitHubClientImpl[IO](token, concurrency, timeout)

      val getRateLimit = PrivateMethod[Option[Long]]('getRateLimitTimeIfLimited)

      val headers = Vector(new Header("X-RateLimit-Remaining", "0"), new Header("X-RateLimit-Reset", "1377013266"))

      val response = Response("".asRight[String], StatusCode.Forbidden, StatusCode.Forbidden.toString(), headers, List.empty[Response[Unit]])

      (gitHub invokePrivate getRateLimit(response)).value should be(1377013266L)
    }

    "set rate limited status on rate limit error and return failed IO with RateLimited exception" in {
      val gitHub = new GitHubClientImpl[IO](token, concurrency, timeout)
      val checkAndUpdateIfLimited = PrivateMethod[IO[Response[Either[String, String]]]]('checkAndUpdateIfLimited)

      val getRateLimit = PrivateMethod[Option[Long]]('getRateLimitTimeIfLimited)
      val headers = Vector(new Header("X-RateLimit-Remaining", "0"), new Header("X-RateLimit-Reset", "1377013266"))

      val response = Response("".asRight[String], StatusCode.Forbidden, StatusCode.Forbidden.toString(), headers, List.empty[Response[Unit]])
      val future = (gitHub invokePrivate checkAndUpdateIfLimited(IO.pure(response))).unsafeToFuture()
      (gitHub invokePrivate getRateLimit(response)).value should be(1377013266L)
      an[RateLimited] should be thrownBy Await.result(future, 1.second)
    }

    "correctly parse page count from response" in {
      val gitHub = new GitHubClientImpl[IO](token, concurrency, timeout)
      val getPageCount = PrivateMethod[Int]('getPageCount)
      val response = Response("".asRight[String], StatusCode.Ok, StatusCode.Ok.toString(), page1headers, List.empty[Response[Unit]])
      (gitHub invokePrivate getPageCount(response)) should be(2)
    }

    "correctly parse page body with list of repositories" in {
      val gitHub = new GitHubClientImpl[IO](token, concurrency, timeout)
      val parseResponse = PrivateMethod[IO[Seq[GitHubRepository]]]('parseRepositoryResponse)
      val result = (gitHub invokePrivate parseResponse(IO.pure(page1RepositoryResponse))).unsafeRunSync()
      result.size should be(2)
    }

    "correctly parse page body with list of contributors" in {
      val gitHub = new GitHubClientImpl[IO](token, concurrency, timeout)
      val parseResponse = PrivateMethod[IO[Seq[Contributor]]]('parseContributorResponse)

      val response = Response(
        config.getString("contributor_list_body").asRight[String],
        StatusCode.Ok,
        StatusCode.Ok.toString(),
        Vector.empty[Header],
        List.empty[Response[Unit]]
      )
      val result = (gitHub invokePrivate parseResponse(IO.pure(response))).unsafeRunSync()
      result.size should be(2)
    }

    "return result from all pages" in {
      val gitHub = new GitHubClientImpl[IO](token, concurrency, timeout)
      val result = gitHub.getRepos("test").unsafeRunSync()
      result.size should be(4)
    }

    "return failed IO with DecodingJsonError exception on incorrect Json" in {
      val gitHub = new GitHubClientImpl[IO](token, concurrency, timeout)
      val parseResponse = PrivateMethod[IO[Seq[GitHubRepository]]]('parseRepositoryResponse)

      val response = Response(
        config.getString("incorrect_repository_list_body").asRight[String],
        StatusCode.Ok,
        StatusCode.Ok.toString(),
        Vector.empty[Header],
        List.empty[Response[Unit]]
      )
      an[DecodingJsonError] should be thrownBy (gitHub invokePrivate parseResponse(IO.pure(response))).unsafeRunSync()
    }

    "return failed IO with RateLimited exception on rate limited response from GitHub" in {
      val gitHub = new GitHubClientImpl[IO](token, concurrency, timeout)
      an[RateLimited] should be thrownBy gitHub.getRepos("test_rate").unsafeRunSync()
    }
  }
}
