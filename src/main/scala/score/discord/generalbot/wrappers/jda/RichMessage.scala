package score.discord.generalbot.wrappers.jda

import net.dv8tion.jda.core.entities.Message
import score.discord.generalbot.collections.ReplyCache
import score.discord.generalbot.functionality.ownership.MessageOwnership
import score.discord.generalbot.wrappers.Tap._
import score.discord.generalbot.wrappers.jda.Conversions._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class RichMessage(val me: Message) extends AnyVal {
  def reply(contents: MessageFromX)(implicit mo: MessageOwnership, replyCache: ReplyCache): Future[Message] =
    me.getChannel.sendOwned(contents, me.getAuthor).tap(_.foreach { message =>
      replyCache += me.id -> message.id
    })
}
