package serhii.service

import cats.effect.Concurrent
import cats.effect.implicits._
import cats.implicits._
import cats.{Applicative, FlatMap, Parallel}
import com.typesafe.scalalogging.LazyLogging
import io.circe.Decoder
import io.circe.parser.decode
import serhii.model.{Contributor, GitHubRepository}
import serhii.utils.Effects._
import serhii.utils.Errors.{DecodingJsonError, RateLimited, UnknownGitHubError}
import sttp.client._

import scala.concurrent.duration._
import scala.util.Try

class GitHubClientImpl[F[_]: Concurrent: Parallel: MonadThrow](val token: String, val concurrency: Int, val timeout: Int)(
             implicit val backend: SttpBackend[F, Nothing, NothingT]
) extends GitHubClient[F]
    with LazyLogging {

  type ClientResponse = Response[Either[String, String]]

  private val RateLimitRemainingHeader = "X-RateLimit-Remaining"
  private val RateLimitResetTimeHeader = "X-RateLimit-Reset"

  protected var rateLimitReset: Option[Long] = None

  protected def getRateLimitResetStatus: Option[Long] = rateLimitReset

  protected def setRateLimitResetStatus(value: Option[Long]): Unit = {
    rateLimitReset = value
  }

  private def checkAndParse[T](request: F[ClientResponse], f: F[ClientResponse] => F[Vector[T]]): F[Vector[T]] =
    for {
      _ <- checkAndUpdateIfLimited(request)
      result <- f(request)
    } yield result

  protected def getDataFromPages[T](parseFunction: F[ClientResponse] => F[Vector[T]], formRequestFunction: Int => F[ClientResponse]): F[Vector[T]] = {
    val firstRequest = formRequestFunction(1)
    val task = for {
      firstPageRes <- checkAndParse(firstRequest, parseFunction)
      lastPage <- firstRequest.map(resp => getPageCount(resp))
    } yield {
      val pages = (2 to lastPage).map { page =>
        val request: F[ClientResponse] = formRequestFunction(page)
        checkAndParse(request, parseFunction)
      }.toVector
      Concurrent[F].parTraverseN(concurrency)(pages)(identity).map(_.flatten).map(_ ++ firstPageRes)
    }
    task.flatten
  }

  override def getRepos(organization: String): F[Vector[GitHubRepository]] =
    getDataFromPages(parseRepositoryResponse, formRepositoriesRequest(_, organization, token))

  override def getContributors(repositories: Vector[GitHubRepository]): F[Vector[Contributor]] = {

    val parsed: Vector[F[Vector[Contributor]]] = repositories.map { repository =>
      getDataFromPages(parseContributorResponse, formContributorsRequest(_, repository.contributorsUrl, token))
    }

    Concurrent[F].parTraverseN(concurrency)(parsed)(identity).map(_.flatten).map(groupAndSortByContribution)
  }

  private def groupAndSortByContribution(contributions: Vector[Contributor]): Vector[Contributor] = {
    contributions
      .groupBy(_.login)
      .mapValues(_.map(_.contributions).sum)
      .map { case (login, contributions) => Contributor(login, contributions) }
      .toVector
      .sortBy(_.contributions)(Ordering.Int.reverse)
  }

  private def formRepositoriesRequest(page: Int, organization: String, token: String): F[ClientResponse] = {
    formRequest(s"https://api.github.com/orgs/$organization/repos?page=$page", token)
  }

  private def formContributorsRequest(page: Int, url: String, token: String): F[ClientResponse] = {
    formRequest(s"$url?page=$page", token)
  }

  private def formRequest(url: String, token: String): F[ClientResponse] = {
    resetLimitedStatus().fold {
      basicRequest.get(uri"$url").header("Authorization", s"token $token", true).response(asString("UTF-8")).readTimeout(timeout.seconds).send()
    }(time => (RateLimited(time).raiseError[F, ClientResponse]))

  }

  private def checkAndUpdateIfLimited(response: F[ClientResponse]): F[ClientResponse] = {
    implicitly[FlatMap[F]].flatMap(response) { resp =>
      getRateLimitTimeIfLimited(resp).fold(Applicative[F].pure(resp)) { time =>
        setRateLimitResetStatus(Some(time))
        (RateLimited(time)).raiseError[F, ClientResponse]
      }
    }
  }

  protected def resetLimitedStatus(): Option[Long] = {
    getRateLimitResetStatus.flatMap { resetTime =>
      if (resetTime <= java.time.Instant.now().getEpochSecond) {
        setRateLimitResetStatus(None)
        None
      } else {
        Some(resetTime)
      }
    }
  }

  private def getRateLimitTimeIfLimited(response: ClientResponse): Option[Long] = {
    if (response.code.code == 403) {
      val remaining = response.header(RateLimitRemainingHeader).flatMap(rem => Try(rem.toInt).toOption)
      val resetTime = response.header(RateLimitResetTimeHeader).flatMap(rem => Try(rem.toLong).toOption)
      for {
        rem <- remaining
        time <- resetTime
        if rem == 0
      } yield time
    } else None
  }

  private def getPageCount(response: ClientResponse): Int = {
    if (response.code.isSuccess) {
      response
        .header("Link")
        .fold(1)(header => {
          val result = for {
            step1 <- header.split(",").find(_.contains("last"))
            step2 <- step1.split(";").headOption
            page <- Try(step2.split("=")(1).dropRight(1).toInt).toOption
          } yield page
          result.getOrElse {
            1
          }
        })
    } else {
      1
    }
  }

  private def parseRepositoryResponse(response: F[ClientResponse]): F[Vector[GitHubRepository]] =
    parseResponse[Vector[GitHubRepository]](response)

  private def parseContributorResponse(response: F[ClientResponse]): F[Vector[Contributor]] =
    parseResponse[Vector[Contributor]](response)

  private def parseResponse[T: Decoder](response: F[ClientResponse]): F[T] = {
    FlatMap[F].flatMap(response) { resp =>
      if (resp.code.isSuccess) {
        resp.body.fold(err => UnknownGitHubError(err).raiseError[F, T], body => {
          decode[T](body).fold(err => DecodingJsonError(err.getMessage).raiseError[F, T], Applicative[F].pure(_))
        })
      } else UnknownGitHubError(resp.statusText).raiseError[F, T]
    }

  }

}
