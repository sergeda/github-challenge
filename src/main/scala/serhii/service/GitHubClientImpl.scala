package serhii.service

import cats.effect.Concurrent
import cats.effect.implicits._
import cats.implicits._
import cats.{Applicative, FlatMap, MonadError, Parallel}
import com.typesafe.scalalogging.LazyLogging
import io.circe.Decoder
import io.circe.parser.decode
import serhii.model.{Contributor, GitHubRepository}
import serhii.utils.Errors.{DecodingJsonError, RateLimited, UnknownGitHubError}
import sttp.client._

import scala.concurrent.duration._
import scala.util.Try

class GitHubClientImpl[F[_]: Concurrent: Parallel](val token: String, val concurrency: Int, val timeout: Int)(
             implicit m: MonadError[F, Throwable],
             val backend: SttpBackend[F, Nothing, NothingT]
) extends GitHubClient[F]
    with LazyLogging {

  private val RateLimitRemainingHeader = "X-RateLimit-Remaining"
  private val RateLimitResetTimeHeader = "X-RateLimit-Reset"

  protected var rateLimitReset: Option[Long] = None

  protected def getRateLimitResetStatus: Option[Long] = rateLimitReset

  protected def setRateLimitResetStatus(value: Option[Long]): Unit = {
    rateLimitReset = value
  }

  private def checkAndParse[T](request: F[Response[Either[String, String]]], f: F[Response[Either[String, String]]] => F[Vector[T]]): F[Vector[T]] =
    for {
      _ <- checkAndUpdateIfLimited(request)
      result <- f(request)
    } yield result

  protected def getDataFromPages[T](
               parseFunction: F[Response[Either[String, String]]] => F[Vector[T]],
               formRequestFunction: Int => F[Response[Either[String, String]]]
  ): F[Vector[T]] = {
    val firstRequest = formRequestFunction(1)
    val task = for {
      firstPageRes <- checkAndParse(firstRequest, parseFunction)
      lastPage <- firstRequest.map(resp => getPageCount(resp))
    } yield {
      val pages = (2 to lastPage).map { page =>
        val request: F[Response[Either[String, String]]] = formRequestFunction(page)
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

  private def formRepositoriesRequest(page: Int, organization: String, token: String): F[Response[Either[String, String]]] = {
    formRequest(s"https://api.github.com/orgs/$organization/repos?page=$page", token)
  }

  private def formContributorsRequest(page: Int, url: String, token: String): F[Response[Either[String, String]]] = {
    formRequest(s"$url?page=$page", token)
  }

  private def formRequest(url: String, token: String): F[Response[Either[String, String]]] = {
    resetLimitedStatus().fold {
      basicRequest.get(uri"$url").header("Authorization", s"token $token", true).response(asString("UTF-8")).readTimeout(timeout.seconds).send()
    }(time => m.raiseError(RateLimited(time)))

  }

  private def checkAndUpdateIfLimited(response: F[Response[Either[String, String]]]): F[Response[Either[String, String]]] = {
    implicitly[FlatMap[F]].flatMap(response) { resp =>
      getRateLimitTimeIfLimited(resp).fold(Applicative[F].pure(resp)) { time =>
        setRateLimitResetStatus(Some(time))
        m.raiseError(RateLimited(time))
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

  private def getRateLimitTimeIfLimited(response: Response[Either[String, String]]): Option[Long] = {
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

  private def getPageCount(response: Response[Either[String, String]]): Int = {
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

  private def parseRepositoryResponse(response: F[Response[Either[String, String]]]): F[Vector[GitHubRepository]] =
    parseResponse[Vector[GitHubRepository]](response)

  private def parseContributorResponse(response: F[Response[Either[String, String]]]): F[Vector[Contributor]] =
    parseResponse[Vector[Contributor]](response)

  private def parseResponse[T: Decoder](response: F[Response[Either[String, String]]]): F[T] = {
    FlatMap[F].flatMap(response) { resp =>
      if (resp.code.isSuccess) {
        resp.body.fold(err => m.raiseError(UnknownGitHubError(err)), body => {
          decode[T](body).fold(err => m.raiseError(DecodingJsonError(err.getMessage)), Applicative[F].pure(_))
        })
      } else m.raiseError(UnknownGitHubError(resp.statusText))
    }

  }

}
