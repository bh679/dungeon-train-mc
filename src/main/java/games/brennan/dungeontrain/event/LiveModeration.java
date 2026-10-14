package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.discord.WorldInfoReporter;
import games.brennan.dungeontrain.moderation.LiveModerationLadder;
import games.brennan.dungeontrain.moderation.LiveModerationLadder.Action;
import games.brennan.dungeontrain.moderation.LiveModerationLadder.Verdict;
import games.brennan.dungeontrain.moderation.LiveModerationScope;
import games.brennan.dungeontrain.moderation.LiveWordList;
import games.brennan.dungeontrain.net.LiveModerationStrikePacket;
import games.brennan.dungeontrain.net.relay.LiveModerationClient;
import games.brennan.dungeontrain.net.relay.LiveWordListFetcher;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import games.brennan.dungeontrain.player.ServerLiveStatus;
import games.brennan.dungeontrain.util.PresenceLine;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server half of the Live Eye text filter: who has struck out, and what it costs them.
 *
 * <p>The relay ({@code livestrikes.js}) owns the ladder. This class mirrors each online player's
 * verdict ({@link #isBanned}), reports every masked word as a strike through the durable
 * {@link RelayOutbox}, and acts on the relay's answer: a {@code remove} burns the Live Eye and ends
 * the stream ({@link LiveFeedEvents#endForModeration}); a {@code ban} does the same and marks the
 * player so a Live Eye can't be worn or looted ({@link LiveEyeLootResolver}) until it lapses.</p>
 *
 * <p>The player hears a warning the moment a word is masked, counted locally against the ladder the
 * relay published ({@link LiveWordList#ladder()}); the relay's reply then does the acting. A relay
 * outage therefore costs nothing but the warning text being a guess. Strikes are rate-limited to one
 * per player per {@link #STRIKE_GAP_MS}: the client and the server both see the same chat line.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LiveModeration {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One outburst, one strike — the client's as-typed report and the server's on-send pass overlap. */
    static final long STRIKE_GAP_MS = 10_000L;

    private static final String KEY = "chat.dungeontrain.live.";

    /** A verdict and when it arrived, so a timed ban is measured from arrival, not from the relay's clock. */
    record Cached(Verdict verdict, long receivedAtMs) {}

    private static final Map<UUID, Cached> VERDICTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_STRIKE_MS = new ConcurrentHashMap<>();

    private LiveModeration() {}

    /** Register the outbox response handler. Called once at mod setup. */
    public static void register() {
        RelayOutbox.get().onResponse(LiveModerationClient.STRIKE_PATH, LiveModeration::onStrikeResponse);
    }

    // --- state ------------------------------------------------------------------------------------

    /** Whether {@code player} may not wear or loot a Live Eye right now. */
    public static boolean isBanned(UUID player) {
        Cached c = VERDICTS.get(player);
        return c != null && !c.verdict().mayStream(System.currentTimeMillis(), c.receivedAtMs());
    }

    /** Seconds of ban left for {@code player}; 0 when not banned, {@link Long#MAX_VALUE} when permanent. */
    public static long banRemainingSec(UUID player) {
        Cached c = VERDICTS.get(player);
        return c == null ? 0 : c.verdict().remainingAt(System.currentTimeMillis(), c.receivedAtMs());
    }

    static void apply(UUID player, Verdict v, long nowMs) {
        VERDICTS.put(player, new Cached(v, nowMs));
    }

    // --- lifecycle --------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        LiveWordListFetcher.ensureFetched();
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        UUID uuid = player.getUUID();
        LiveModerationClient.fetchStatus(uuid, v -> server.execute(() -> apply(uuid, v, System.currentTimeMillis())));
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_STRIKE_MS.remove(event.getEntity().getUUID());
        // The verdict stays cached: a relog within the session must not forget a ban.
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        VERDICTS.clear();
        LAST_STRIKE_MS.clear();
    }

    // --- strikes ----------------------------------------------------------------------------------

    /** The server itself masked a word in {@code player}'s chat line or book. */
    public static void strike(ServerPlayer player, String source, List<String> hits) {
        record(player, source, hits);
    }

    /** The player's client says it masked a word as they typed. Counted only while the filter is on for them. */
    public static void strikeFromClient(ServerPlayer player, LiveModerationStrikePacket.Source source, List<String> hits) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        if (!LiveModerationScope.serverActive(ServerLiveStatus.isAnyoneLive(server))) return;
        record(player, source.relayName(), hits);
    }

    private static void record(ServerPlayer player, String source, List<String> hits) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUUID();
        Long last = LAST_STRIKE_MS.get(uuid);
        if (last != null && now - last < STRIKE_GAP_MS) return;
        LAST_STRIKE_MS.put(uuid, now);

        Cached c = VERDICTS.get(uuid);
        int strikesAfter = (c == null ? 0 : c.verdict().strikes()) + 1;
        LiveWordList.Ladder ladder = LiveWordList.ladder();
        player.sendSystemMessage(warning(source, strikesAfter, ladder));
        LOGGER.info("[DungeonTrain] live moderation: {} masked in {}'s {} (local strike {} of {})",
            hits.size(), player.getGameProfile().getName(), source, strikesAfter, ladder.removeAtStrike());

        try {
            RelayOutbox.get().enqueue(LiveModerationClient.STRIKE_PATH,
                LiveModerationClient.strikeBody(uuid, player.getGameProfile().getName(), source, hits));
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] live moderation strike could not be queued: {}", t.toString());
        }
    }

    /** The line a player hears when a word is masked: which warning this is, and what the next step costs. */
    static Component warning(String source, int strikesAfter, LiveWordList.Ladder ladder) {
        boolean book = source != null && source.startsWith("book");
        int shown = Math.min(strikesAfter, ladder.warningsBeforeRemoval());
        if (LiveModerationLadder.decideLocally(strikesAfter, ladder) == Action.REMOVE) {
            return Component.translatable(KEY + (book ? "censored_book_final" : "censored_final")).withStyle(ChatFormatting.RED);
        }
        return Component.translatable(KEY + (book ? "censored_book" : "censored"),
            shown, ladder.warningsBeforeRemoval()).withStyle(ChatFormatting.RED);
    }

    // --- the relay's answer -----------------------------------------------------------------------

    static void onStrikeResponse(String requestBody, int status, String responseBody) {
        if (status / 100 != 2) return;
        Verdict v = LiveModerationLadder.parse(responseBody);
        if (v == null) return;
        UUID player = LiveModerationClient.playerOf(requestBody);
        if (player == null) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        server.execute(() -> {
            try {
                apply(player, v, System.currentTimeMillis());
                ServerPlayer online = server.getPlayerList().getPlayer(player);
                if (online == null) return;
                switch (v.action()) {
                    case REMOVE -> LiveFeedEvents.endForModeration(online, false);
                    case BAN -> {
                        LiveFeedEvents.endForModeration(online, true);
                        online.sendSystemMessage(bannedLine(online));
                    }
                    default -> { }
                }
            } catch (Throwable t) {
                LOGGER.debug("[DungeonTrain] live moderation verdict could not be applied: {}", t.toString());
            }
        });
    }

    /** "You can't wear the Live Eye for another N days" / "…no longer wear the Live Eye". */
    public static Component bannedLine(ServerPlayer player) {
        long left = banRemainingSec(player.getUUID());
        if (left == Long.MAX_VALUE) {
            return Component.translatable(KEY + "banned_permanent").withStyle(ChatFormatting.RED);
        }
        String locale = WorldInfoReporter.clientLanguage(player);
        return Component.translatable(KEY + "banned",
            PresenceLine.agoComponent(locale, Duration.ofSeconds(Math.max(1L, left)))).withStyle(ChatFormatting.RED);
    }
}
