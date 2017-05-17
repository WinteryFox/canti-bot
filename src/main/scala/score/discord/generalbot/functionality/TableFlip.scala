package score.discord.generalbot.functionality

import net.dv8tion.jda.core.events.Event
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import net.dv8tion.jda.core.hooks.EventListener
import score.discord.generalbot.functionality.ownership.MessageOwnership
import score.discord.generalbot.wrappers.jda.Conversions._

class TableFlip(implicit messageOwnership: MessageOwnership) extends EventListener {
  val flip = "(╯°□°）╯︵ ┻━┻"
  val unflip = "┬─┬﻿ ノ( ゜-゜ノ)"

  override def onEvent(event: Event): Unit = {
    event match {
      case ev: MessageReceivedEvent =>
        val message = ev.getMessage
        if (message.getAuthor.isBot) return

        val text = message.getRawContent
        if (text contains flip) {
          message.getChannel.sendOwned(unflip, ev.getAuthor)
        } else if (text contains unflip) {
          message.getChannel.sendOwned(flip, ev.getAuthor)
        }

      case _ =>
    }
  }
}
