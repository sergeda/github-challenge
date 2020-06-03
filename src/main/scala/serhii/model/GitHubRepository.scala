package serhii.model

import io.circe.{Decoder, HCursor}

case class GitHubRepository(contributorsUrl: String)
case object GitHubRepository {
  implicit def jsonDecoder: Decoder[GitHubRepository] = (c: HCursor) => {
    for {
      url <- c.downField("contributors_url").as[String]
    } yield {
      new GitHubRepository(url)
    }
  }
}
