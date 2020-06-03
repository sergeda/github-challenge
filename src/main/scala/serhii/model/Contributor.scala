package serhii.model

import io.circe._
import io.circe.generic.semiauto._

case class Contributor(login: String, contributions: Int)

object Contributor {
  implicit val jsonDecoder: Decoder[Contributor] = deriveDecoder[Contributor]
  implicit val jsonEncoder: Encoder[Contributor] = (c: Contributor) =>
    Json.obj(("contributor_login", Json.fromString(c.login)), ("contributions", Json.fromInt(c.contributions)))
}
