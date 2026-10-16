package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.moderation.LiveModerationScope;
import games.brennan.dungeontrain.moderation.LiveWordList;
import games.brennan.dungeontrain.moderation.PatternCensor;
import games.brennan.dungeontrain.moderation.TextCensor;
import games.brennan.dungeontrain.player.ServerLiveStatus;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ServerChatEvent;

/**
 * Server pass of the Live Eye text filter over chat: while anyone is live, every player's line is
 * censored before it is broadcast — another player's slur shows on the streamer's screen too.
 *
 * <p>Runs at {@link EventPriority#LOWEST}: the bundled Discord Presence listener (NORMAL) rebuilds the
 * message from its raw text on a {@code @dev} line, so the censor must have the last word. It never
 * cancels. On a secure-chat client a server-rewritten line carries vanilla's "Modified" marker — the
 * streamer's own client censors before signing, so the common case stays unmarked.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LiveChatCensorEvents {

    private LiveChatCensorEvents() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerChat(ServerChatEvent event) {
        try {
            MinecraftServer server = event.getPlayer().getServer();
            if (!LiveModerationScope.serverActive(ServerLiveStatus.isAnyoneLive(server))) return;
            String raw = event.getMessage().getString();
            TextCensor.Result r = TextCensor.censor(raw, LiveWordList.effective());
            // Links and phone numbers: hidden on the same scope, but never a strike.
            TextCensor.Result p = PatternCensor.censor(r.text());
            if (!r.changed() && !p.changed()) return;
            event.setMessage(Component.literal(p.text()));
            if (r.changed()) LiveModeration.strike(event.getPlayer(), "chat_server", r.hits(), raw);
            if (p.changed()) LiveModeration.warnLinkHidden(event.getPlayer());
        } catch (Throwable t) {
            // Fail open: a filter fault must never eat a chat line.
        }
    }
}
