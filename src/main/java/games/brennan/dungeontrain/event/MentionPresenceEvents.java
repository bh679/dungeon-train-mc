package games.brennan.dungeontrain.event;

import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.util.PresenceLine;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ServerChatEvent;

import java.time.Instant;
import java.util.Locale;

/**
 * When a player types {@code @dev} or {@code @brennanhatton} in chat, privately tells the SENDER how
 * recently Brennan was last seen online on Discord — but only when he is online now or was seen within
 * {@link PresenceLine#MENTION_PRESENCE_WINDOW}; otherwise stays silent. This sets the tagging player's
 * expectation of a timely reply without changing anything else: the line is the only addition.
 *
 * <p>Observe-only — never cancels or edits the message — so the bundled Discord Presence mod's own
 * {@code ServerChatEvent} listener still relays the line and rewrites the {@code @}-token to a real
 * Discord ping. Detection mirrors that mod's matcher exactly (case-insensitive substring over the shared
 * {@link DungeonTrain#MENTION_TOKENS}) so this reply appears precisely when the real ping fires.</p>
 *
 * <p>No {@code value = Dist}: like the relay listener it registers on both sides, so the integrated
 * server (singleplayer) replies too. The presence read is a synchronous in-memory lookup, safe on the
 * server thread.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class MentionPresenceEvents {

    private MentionPresenceEvents() {}

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        if (!mentionsBrennan(event.getRawText())) {
            return;
        }
        ServerPlayer sender = event.getPlayer();
        DiscordService dp = DiscordService.get();
        Component line = PresenceLine.recentLine(
                dp.isDiscordUserOnline(DungeonTrain.BRENNAN_DISCORD_ID),
                dp.lastSeenOnline(DungeonTrain.BRENNAN_DISCORD_ID),
                Instant.now());
        if (line != null) {
            sender.sendSystemMessage(line); // sender only — the public broadcast is untouched
        }
    }

    /**
     * Whether {@code text} contains any mention token, by the bundled Discord Presence mod's exact rule:
     * case-insensitive substring (NOT word-boundary). Mirroring it keeps this reply in lock-step with the
     * real Discord ping — do not tighten to word-boundary or the two desync.
     */
    static boolean mentionsBrennan(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String token : DungeonTrain.MENTION_TOKENS) {
            if (lower.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
