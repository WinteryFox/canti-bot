package score.discord.generalbot.command

import net.dv8tion.jda.core.MessageBuilder
import net.dv8tion.jda.core.entities.Game.GameType
import net.dv8tion.jda.core.entities.{Message, TextChannel}
import score.discord.generalbot.functionality.ownership.MessageOwnership
import score.discord.generalbot.util.{APIHelper, BotMessages, MessageUtils}
import score.discord.generalbot.wrappers.jda.Conversions._

import scala.collection.GenIterable
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class GameStatsCommand(implicit mo: MessageOwnership) extends Command.Anyone {
  private val CHANNEL_REGEX = "<#(\\d+)>".r

  override def name: String = "gamestats"

  override def aliases: GenIterable[String] = List("gs")

  override def description: String = "Lists out the most popular games in the given channel at this point in time"

  override def execute(message: Message, args: String): Unit = {
    Future {
      val channel: Option[TextChannel] = args match {
        case CHANNEL_REGEX(id) =>
          Option(message.getJDA.getTextChannelById(id))
        case "" =>
          Option(message.getTextChannel)
        case str =>
          val argsTrim = str.trim
          if (argsTrim.isEmpty) None
          else Option(message.getGuild).flatMap(_.getTextChannelsByName(argsTrim, true).asScala.headOption)
      }
      val result = channel match {
        case None =>
          Left("Can't find that channel")
        case Some(ch) if !message.getAuthor.canSee(ch) =>
          Left("You aren't in that channel")
        case Some(ch) =>
          val games = (for {
            member <- ch.getMembers.asScala
            if !member.getUser.isBot
            game = member.getGame
            if game ne null
            if game.getType == GameType.DEFAULT
          } yield game.getName).groupBy(identity).mapValues(_.size)

          val rows = games.size min 5
          Right(
            new MessageBuilder()
              .append(s"__Top $rows most popular games in ${ch.getAsMention}__\n")
              .append(games.toVector
                .sortWith((a, b) => a._2 > b._2)
                .take(rows)
                .map { case (unescapedGame, count) =>
                  val game = MessageUtils.escapeFormatting(unescapedGame)
                  s"**$game** ($count users)" }
                .mkString("\n"))
              .stripMentions(message.getGuild)
              .getStringBuilder
              .toString
          )
      }

      message reply result.fold(BotMessages.error, BotMessages.plain)
    }.failed.foreach(APIHelper.loudFailure("finding channel game statistics", message.getChannel))
  }
}
