package lila.setup

import akka.stream.scaladsl._
import chess.format.FEN
import chess.variant.Variant
import chess.{ Clock, Mode, Speed }
import org.joda.time.DateTime
import play.api.data._
import play.api.data.Forms._

import lila.game.Game
import lila.game.IdGenerator
import lila.oauth.AccessToken
import lila.oauth.OAuthScope
import lila.oauth.OAuthServer
import lila.user.User

object SetupBulk {

  val maxGames = 500

  case class BulkFormData(
      tokens: String,
      variant: Variant,
      clock: Clock.Config,
      rated: Boolean,
      pairAt: Option[DateTime],
      startClocksAt: Option[DateTime]
  )

  private def timestampInNearFuture = longNumber(max = DateTime.now.plusDays(1).getMillis)

  def form = Form[BulkFormData](
    mapping(
      "tokens" -> nonEmptyText
        .verifying("Not enough tokens", t => extractTokenPairs(t).nonEmpty)
        .verifying(s"Too many tokens (max: ${maxGames * 2})", t => extractTokenPairs(t).sizeIs < maxGames)
        .verifying(
          "Tokens must be unique",
          t => {
            val tokens = extractTokenPairs(t).view.flatMap { case (w, b) => Vector(w, b) }.toVector
            tokens.size == tokens.distinct.size
          }
        ),
      SetupForm.api.variant,
      "clock"         -> SetupForm.api.clockMapping,
      "rated"         -> boolean,
      "pairAt"        -> optional(timestampInNearFuture),
      "startClocksAt" -> optional(timestampInNearFuture)
    ) {
      (
          tokens: String,
          variant: Option[String],
          clock: Clock.Config,
          rated: Boolean,
          pairTs: Option[Long],
          clockTs: Option[Long]
      ) =>
        BulkFormData(
          tokens,
          Variant orDefault ~variant,
          clock,
          rated,
          pairTs.map { new DateTime(_) },
          clockTs.map { new DateTime(_) }
        )
    }(_ => None)
  )

  private[setup] def extractTokenPairs(str: String): List[(AccessToken.Id, AccessToken.Id)] =
    str
      .split(',')
      .view
      .map(_ split ":")
      .collect { case Array(w, b) =>
        w.trim -> b.trim
      }
      .collect {
        case (w, b) if w.nonEmpty && b.nonEmpty => (AccessToken.Id(w), AccessToken.Id(b))
      }
      .toList

  case class BadToken(token: AccessToken.Id, error: OAuthServer.AuthError)

  case class ScheduledGame(id: Game.ID, white: User.ID, black: User.ID)

  case class ScheduledBulk(
      _id: String,
      by: User.ID,
      games: List[ScheduledGame],
      variant: Variant,
      clock: Clock.Config,
      mode: Mode,
      pairAt: DateTime,
      startClocksAt: Option[DateTime],
      scheduledAt: DateTime,
      pairedAt: Option[DateTime] = None
  ) {
    def userSet = Set(games.flatMap(g => List(g.white, g.black)))
    def collidesWith(other: ScheduledBulk) = {
      pairAt == other.pairAt || startClocksAt == startClocksAt
    } && userSet.exists(other.userSet.contains)
  }
}

final class BulkChallengeApi(oauthServer: OAuthServer, idGenerator: IdGenerator)(implicit
    ec: scala.concurrent.ExecutionContext,
    mat: akka.stream.Materializer
) {

  import SetupBulk._

  def apply(data: BulkFormData, me: User): Fu[Either[List[BadToken], ScheduledBulk]] =
    Source(extractTokenPairs(data.tokens))
      .mapConcat { case (whiteToken, blackToken) =>
        List(whiteToken, blackToken) // flatten now, re-pair later!
      }
      .mapAsync(8) { token =>
        oauthServer.auth(token, List(OAuthScope.Challenge.Write)) map {
          _.left.map { BadToken(token, _) }
        }
      }
      .runFold[Either[List[BadToken], List[User.ID]]](Right(Nil)) {
        case (Left(bads), Left(bad))       => Left(bad :: bads)
        case (Left(bads), _)               => Left(bads)
        case (Right(_), Left(bad))         => Left(bad :: Nil)
        case (Right(users), Right(scoped)) => Right(scoped.user.id :: users)
      }
      .flatMap {
        case Left(errors) => fuccess(Left(errors.reverse))
        case Right(allPlayers) =>
          val pairs = allPlayers.reverse
            .grouped(2)
            .collect { case List(w, b) => (w, b) }
            .toList
          lila.mon.api.challenge.bulk.scheduleNb(me.id).increment(pairs.size).unit
          idGenerator
            .games(pairs.size)
            .map {
              _.toList zip pairs
            }
            .map {
              _.map { case (id, (w, b)) =>
                ScheduledGame(id, w, b)
              }
            }
            .dmap {
              ScheduledBulk(
                _id = lila.common.ThreadLocalRandom nextString 8,
                by = me.id,
                _,
                data.variant,
                data.clock,
                Mode(data.rated),
                pairAt = data.pairAt | DateTime.now,
                startClocksAt = data.startClocksAt,
                scheduledAt = DateTime.now
              )
            }
            .dmap(Right.apply)
      }
}