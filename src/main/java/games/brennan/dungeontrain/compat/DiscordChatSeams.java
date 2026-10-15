package games.brennan.dungeontrain.compat;

import games.brennan.discordpresence.compat.DiscordChatHooks;
import games.brennan.discordpresence.compat.DiscordCommandHooks;
import games.brennan.discordpresence.compat.DiscordReactionHooks;
import games.brennan.dungeontrain.discord.chat.ChatReaction;
import games.brennan.dungeontrain.discord.chat.ChatReactionEvent;
import games.brennan.dungeontrain.discord.chat.DiscordChatLines;

import java.util.ArrayList;
import java.util.List;

/**
 * DiscordPresence 0.69.0+ seams for Discord reactions in game chat, kept apart from
 * {@link DiscordInboundBridge} so a DP without them fails here — with a {@link LinkageError} the bridge
 * catches — rather than when the bridge itself loads.
 *
 * <ul>
 *   <li>the message-id-carrying command handler (dev messages keep their Discord id);</li>
 *   <li>a player's chat line posted to Discord → its id, bound on every client;</li>
 *   <li>DP's own Discord→game relay lines, printed by DT with their id;</li>
 *   <li>reaction snapshots from the relay gateway.</li>
 * </ul>
 */
final class DiscordChatSeams {

    private DiscordChatSeams() {}

    static void install() {
        DiscordCommandHooks.installContext((ctx, reply) -> DiscordInboundBridge.onCommand(
            ctx.authorId(), ctx.content(), ctx.targetPlayerUuid(), ctx.messageId(), reply));
        DiscordChatHooks.installPosted((player, rawText, channelId, messageId) ->
            DiscordChatLines.onGameChatPosted(player, rawText, messageId));
        DiscordChatHooks.installDeliverer((recipient, ctx, defaultLine) -> {
            if (ctx.messageId() == null) return false;
            DiscordChatLines.deliver(recipient, ctx.messageId(), defaultLine);
            return true;
        });
        DiscordReactionHooks.install(snapshot -> {
            List<ChatReaction> list = new ArrayList<>();
            for (DiscordReactionHooks.Reaction r : snapshot.list()) {
                list.add(new ChatReaction(r.name(), r.id() == null ? "" : r.id(), r.animated(), r.count(), r.users()));
            }
            DiscordReactionHooks.ReactionEvent e = snapshot.event();
            ChatReactionEvent event = e == null ? null
                : new ChatReactionEvent(e.action(), e.name(), e.id() == null ? "" : e.id(), e.userName());
            DiscordChatLines.onReactions(snapshot.messageId(), list, event);
        });
    }
}
