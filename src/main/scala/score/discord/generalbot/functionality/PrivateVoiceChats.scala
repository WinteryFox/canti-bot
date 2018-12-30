package score.discord.generalbot.functionality

import java.util
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities._
import net.dv8tion.jda.core.events.guild.voice.GuildVoiceUpdateEvent
import net.dv8tion.jda.core.events.{Event, ReadyEvent}
import net.dv8tion.jda.core.exceptions.PermissionException
import net.dv8tion.jda.core.hooks.EventListener
import net.dv8tion.jda.core.requests.restaction.ChannelAction
import score.discord.generalbot.collections.{ReplyCache, UserByChannel}
import score.discord.generalbot.command.Command
import score.discord.generalbot.functionality.ownership.MessageOwnership
import score.discord.generalbot.util._
import score.discord.generalbot.wrappers.Scheduler
import score.discord.generalbot.wrappers.jda.Conversions._
import score.discord.generalbot.wrappers.jda.ID

import scala.async.Async._
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

class PrivateVoiceChats(userByChannel: UserByChannel, commands: Commands)(implicit scheduler: Scheduler, messageOwnership: MessageOwnership, replyCache: ReplyCache) extends EventListener {
  private val invites = new ConcurrentHashMap[GuildUserId, Invite]()

  private type Timestamp = Long

  case class Invite(from: ID[User], channel: ID[Channel], expiry: Timestamp) {
    def valid = System.currentTimeMillis() < expiry
  }

  {
    val accept = new Command.Anyone {
      override def name = "accept"

      override def aliases = List("acc")

      override def description = "Accept another user's invitation to join a voice channel"

      override def execute(message: Message, args: String): Unit = {
        val channel = message.getChannel

        try message.delete.queue()
        catch {
          case _: PermissionException =>
        }

        def ensureInviteValid(inv: Invite) =
          if (inv.valid) Right(inv)
          else Left("Your last invite expired. Please ask for another.")

        val member = CommandHelper(message).member
        val result = for {
          member <- member
          inv <- Option(invites get GuildUserId(member))
            .toRight("You don't have any pending voice chat invitations.")
          _ <- ensureInviteValid(inv)
          voiceChannel <- message.getGuild.findVoiceChannel(inv.channel)
            .toRight("The voice channel you were invited to no longer exists.")
          memberName = member.getEffectiveName
          voiceMention = s"<#${voiceChannel.rawId}>"
        } yield APIHelper.tryRequest(
          message.getGuild.getController.moveVoiceMember(member, voiceChannel),
          onFail = sendChannelMoveError(channel)
        ).foreach { _ =>
          invites.remove(GuildUserId(member))
          channel sendTemporary BotMessages
            .okay(s"Moved you into the $voiceMention channel.")
            .setTitle(s"$memberName: Success!", null)
        }

        for (err <- result.left) {
          val errEmbed = BotMessages.error(err)
          for (member <- member)
            errEmbed.setTitle(s"${member.getEffectiveName}: Error", null)
          channel sendTemporary errEmbed
        }
      }
    }

    val invite = new Command.Anyone {
      override def name = "invite"

      override val aliases = List("inv")

      override def description = "Ask another user to join your current voice channel"

      override def execute(message: Message, args: String): Unit = {
        val response = (for {
          member <- CommandHelper(message).member
          chan <- Option(member.getVoiceState.getChannel)
            .toRight("You must be in voice chat to use this command.")
          success <- message.getMentionedUsers.asScala match {
            case Seq() => Left("You must mention the users you want to join you in voice chat.")
            case Seq(mentions@_*) =>
              for (mention <- mentions)
                invites.put(
                  GuildUserId(chan.getGuild.id, mention.id),
                  Invite(message.getAuthor.id, chan.id, System.currentTimeMillis() + (10 minutes).toMillis)
                )

              val mentioned = mentions map {
                case you if you == message.getAuthor => ">(You)"
                case user => user.mention
              }
              Right(
                s"""${mentioned mkString ", "}:
                   |You have been invited to join ${message.getAuthor.mention} in voice chat.
                   |Please join a voice channel and type `${commands.prefix}${accept.name}`
                   |to accept this invitation.""".stripMargin
              )
          }
        } yield success).fold(BotMessages.error(_): MessageFromX, x => x: MessageFromX)
        message reply response
      }
    }

    commands register accept
    commands register invite
    commands register new Command.ServerAdminDiscretion {
      override def name = "private"

      override val aliases = List("prv", "pv", "voice")

      override def description = "Create a private voice chat channel"

      override def longDescription(invocation: String) =
        s"""This command creates a semi-private voice channel.
           |You can set a user limit (e.g. `$invocation 4`), or leave it blank to make it completely private.
           |You may also invite other users there using the `$invocation command.
           |The name of the channel can be set by adding it to the end of the command.
           |e.g. `$invocation 3 Hangout number 1`""".stripMargin

      override def execute(message: Message, args: String): Unit = {
        val result =
          for {
            member <- CommandHelper(message).member
            voiceChannel <- Option(member.getVoiceState.getChannel)
              .toRight("You need to join voice chat before you can do this.")
            (limit, name) = parseChannelDetails(args, voiceChannel)
            guild = message.getGuild
            channelReq <- createChannel(name, guild)
          } yield {
            val channel = message.getChannel
            async {
              addChannelPermissions(channelReq, member, limit)
              channelReq setParent voiceChannel.getParent

              val newVoiceChannel = await(channelReq.queueFuture())

              userByChannel.synchronized {
                userByChannel(newVoiceChannel) = message.getAuthor
              }.failed.foreach { ex =>
                APIHelper.failure("saving private channel")(ex)
                newVoiceChannel.delete().queueFuture().failed.foreach(APIHelper.failure("deleting private channel after database error"))
                userByChannel remove newVoiceChannel
              }

              channel sendTemporary BotMessages.okay("Your channel has been created.").setTitle("Success", null)

              // TODO: Fix your shit JDA (asInstanceOf cast)
              APIHelper.tryRequest(
                guild.getController.moveVoiceMember(member, newVoiceChannel.asInstanceOf[VoiceChannel]),
                onFail = sendChannelMoveError(channel)
              )
            }.failed.foreach(APIHelper.loudFailure("creating private channel", channel))
          }

        for (err <- result.left)
          message reply BotMessages.error(err)
      }

      private def addChannelPermissions(channelReq: ChannelAction, member: Member, limit: Int) = {
        val guild = member.getGuild
        if (limit == 0)
        // If no limit, deny access to all users by default
          channelReq
            .addPermissionOverride(
              guild.getPublicRole,
              Collections.emptyList[Permission], util.Arrays.asList(Permission.VOICE_CONNECT)
            )
        else
        // Otherwise, if there is a limit, use that and don't add extra permissions
          channelReq
            .setUserlimit(limit)

        channelReq
          .addPermissionOverride(
            guild.getSelfMember,
            SELF_PRIVATE_CHANNEL_PERMISSIONS, Collections.emptyList[Permission]
          )
          .addPermissionOverride(
            member, CREATOR_PRIVATE_CHANNEL_PERMISSIONS, Collections.emptyList[Permission]
          )
      }

      private def parseChannelDetails(args: String, originalChannel: VoiceChannel) = {
        val trimmedArgs = args.trim
        val (limit, name) = trimmedArgs.split(" ", 2) match {
          case Array(limitStr, name_) => (limitStr, name_.trim)
          case Array(maybeLimit) => (maybeLimit, "")
        }

        val maxNameLen = 100
        Try(limit.toInt).toOption
          .filter(x => x >= 0 && x <= 99)
          .map((_, name))
          .getOrElse((0, trimmedArgs))
        match {
          case (limit_, name_) if name_.length > maxNameLen => (limit_, name_ take maxNameLen)
          case (limit_, name_) if name_.length < 3 => (limit_, s"Private ${originalChannel.name}" take maxNameLen)
          case x => x
        }
      }

      private def createChannel(name: String, guild: Guild) =
        Try(guild.getController.createVoiceChannel(name)).toEither.left.map({
          case _: PermissionException =>
            "I don't have permission to create a voice channel. A server administrator will need to fix this."
          case x =>
            System.err.println("Printing a stack trace for failed channel creation:")
            x.printStackTrace()
            "Unknown error occurred when trying to create your channel."
        })

      override def getIdLong = 253013831501437455L
    }
  }

  private val CREATOR_PRIVATE_CHANNEL_PERMISSIONS =
    util.Arrays.asList(Permission.MANAGE_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_MOVE_OTHERS)

  private val SELF_PRIVATE_CHANNEL_PERMISSIONS =
    util.Arrays.asList(Permission.MANAGE_CHANNEL, Permission.VOICE_CONNECT)

  private def translateChannelMoveError(ex: Throwable) = ex match {
    case _: IllegalStateException =>
      "You need to join voice chat before I can move you into a channel."
    case _: PermissionException =>
      "I don't have permission to move you to another voice channel. A server administrator will need to fix this."
    case _ =>
      APIHelper.failure("moving a user to a newly created channel")(ex)
      "An error occurred while trying to move you to another channel."
  }

  private def sendChannelMoveError(chan: MessageChannel)(ex: Throwable) =
    chan sendTemporary BotMessages.error(translateChannelMoveError(ex))

  override def onEvent(event: Event): Unit = event match {
    case ev: ReadyEvent =>
      val jda = ev.getJDA
      async {
        val allUsersByChannel = await(userByChannel.all)
        val toRemove = new mutable.HashSet[(ID[Guild], ID[Channel])]

        for ((guildId, channelId, _) <- allUsersByChannel) {
          jda.findGuild(guildId).flatMap(_.findVoiceChannel(channelId)) match {
            case None =>
              toRemove += ((guildId, channelId))
            case Some(channel) if channel.getMembers.isEmpty =>
              async {
                await(channel.delete.queueFuture())
                // Note: Sequenced rather than parallel because the channel
                // might not be deleted due to permissions or other reasons.
                await(userByChannel remove channel)
              }.failed.foreach(APIHelper.failure("deleting unused private channel"))
            case _ =>
          }
        }

        val removed = Future.sequence {
          for ((guildId, channelId) <- toRemove)
            yield userByChannel.remove(guildId, channelId)
        }
        await(removed)  // Propagate exceptions
      }.failed.foreach(APIHelper.failure("processing initial private voice chat state"))

    case ev: GuildVoiceUpdateEvent =>
      val channel = ev.getChannelLeft

      if (channel.getMembers.isEmpty)
        async {
          val user = await(userByChannel(channel))
          if (user.isDefined) {
            await(channel.delete.queueFuture())
            await(userByChannel remove channel)
          }
        }.failed.foreach(APIHelper.failure("deleting unused private channel"))

    case _ =>
  }
}
