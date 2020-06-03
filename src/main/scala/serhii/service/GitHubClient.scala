package serhii.service

import serhii.model.{Contributor, GitHubRepository}
import sttp.client.{NothingT, SttpBackend}

trait GitHubClient[F[_]] {

  implicit val backend: SttpBackend[F, Nothing, NothingT]

  val token: String
  val concurrency: Int
  val timeout: Int

  def getRepos(organization: String): F[Vector[GitHubRepository]]
  def getContributors(repositories: Vector[GitHubRepository]): F[Vector[Contributor]]
}
