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
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
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
        RelayOutbox.get().onResponse(LiveModerationClient.APPEAL_PATH, LiveModeration::onAppealResponse);
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
        if (event.getEntity() instanceof ServerPlayer player) refresh(player);
    }

    /** Ask the relay for {@code player}'s verdict again (login, and each refused Live Eye while banned). */
    public static void refresh(ServerPlayer player) {
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

    /** The server itself masked a word in {@code player}'s chat line or book; {@code text} is the line as typed. */
    public static void strike(ServerPlayer player, String source, List<String> hits, String text) {
        record(player, source, hits, text);
    }

    /** The player's client says it masked a word as they typed. Counted only while the filter is on for them. */
    public static void strikeFromClient(ServerPlayer player, LiveModerationStrikePacket.Source source, String text, List<String> hits) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        if (!LiveModerationScope.serverActive(ServerLiveStatus.isAnyoneLive(server))) return;
        record(player, source.relayName(), hits, text);
    }

    private static void record(ServerPlayer player, String source, List<String> hits, String text) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUUID();
        Long last = LAST_STRIKE_MS.get(uuid);
        if (last != null && now - last < STRIKE_GAP_MS) return;
        LAST_STRIKE_MS.put(uuid, now);

        Cached c = VERDICTS.get(uuid);
        int strikesAfter = (c == null ? 0 : c.verdict().strikes()) + 1;
        LiveWordList.Ladder ladder = LiveWordList.ladder();
        Component line = warning(source, strikesAfter, ladder);
        if (line != null) player.sendSystemMessage(line);
        LOGGER.info("[DungeonTrain] live moderation: {} masked in {}'s {} (local strike {} of {})",
            hits.size(), player.getGameProfile().getName(), source, strikesAfter, ladder.removeAtStrike());

        try {
            RelayOutbox.get().enqueue(LiveModerationClient.STRIKE_PATH,
                LiveModerationClient.strikeBody(uuid, player.getGameProfile().getName(), source, hits, text));
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] live moderation strike could not be queued: {}", t.toString());
        }
    }

    /**
     * The line a player hears when a word is masked: which warning this is, out of how many. On the
     * strike that uses the last one up there is no warning — the Live Eye's burn line says it all
     * ({@code chat.dungeontrain.live.burns_moderated}), so null here.
     */
    @Nullable
    static Component warning(String source, int strikesAfter, LiveWordList.Ladder ladder) {
        if (LiveModerationLadder.decideLocally(strikesAfter, ladder) == Action.REMOVE) return null;
        boolean book = source != null && source.startsWith("book");
        int shown = Math.min(strikesAfter, ladder.warningsBeforeRemoval());
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
                    case BAN -> LiveFeedEvents.endForModeration(online, true); // the burn line carries the ban
                    default -> { }
                }
            } catch (Throwable t) {
                LOGGER.debug("[DungeonTrain] live moderation verdict could not be applied: {}", t.toString());
            }
        });
    }

    /** The logged lines behind {@code player}'s ban, newest first; empty when not banned or unknown. */
    public static List<LiveModerationLadder.Evidence> evidenceFor(UUID player) {
        Cached c = VERDICTS.get(player);
        return c == null ? List.of() : c.verdict().evidence();
    }

    /**
     * The burn line of a banned player's Live Eye: "The Live Eye burns away. You can't wear another for
     * N days." (or "…can no longer wear one."), then the lines that earned the ban — each masked, with
     * a clickable [Appeal] that runs {@code /liveappeal <n>} — and the appeal hint.
     */
    public static Component bannedBurnLine(ServerPlayer player) {
        long left = banRemainingSec(player.getUUID());
        MutableComponent out;
        if (left == Long.MAX_VALUE) {
            out = Component.translatable(KEY + "burns_banned_permanent").withStyle(ChatFormatting.RED);
        } else {
            String locale = WorldInfoReporter.clientLanguage(player);
            // Rounded up to the next hour: "7 days" a minute into a week-long ban, not "6 days".
            out = Component.translatable(KEY + "burns_banned",
                PresenceLine.agoComponent(locale, Duration.ofSeconds(Math.max(1L, left) + 3599L))).withStyle(ChatFormatting.RED);
        }
        List<LiveModerationLadder.Evidence> evidence = evidenceFor(player.getUUID());
        if (!evidence.isEmpty()) {
            out.append("\n").append(Component.translatable(KEY + "banned_for").withStyle(ChatFormatting.GRAY));
            out.append(evidenceLines(evidence));
        }
        return out.append("\n").append(Component.translatable(KEY + "appeal_hint").withStyle(ChatFormatting.GRAY));
    }

    /**
     * One line per logged strike: "1. you **** there  [Appeal]". The text is shown masked — the
     * player knows what they typed, and the word must not reappear on anyone's screen. Clicking
     * [Appeal] runs {@code /liveappeal <n>}.
     */
    public static Component evidenceLines(List<LiveModerationLadder.Evidence> evidence) {
        MutableComponent out = Component.empty();
        var words = LiveWordList.effective();
        for (int i = 0; i < evidence.size(); i++) {
            final int n = i + 1;
            LiveModerationLadder.Evidence e = evidence.get(i);
            String shown = games.brennan.dungeontrain.moderation.TextCensor.censor(e.text(), words).text();
            if (shown.isBlank()) shown = String.join(", ", e.terms());
            out.append("\n").append(Component.literal(n + ". ").withStyle(ChatFormatting.DARK_GRAY))
               .append(Component.literal(shown).withStyle(ChatFormatting.GRAY))
               .append(" ")
               .append(Component.translatable(KEY + "appeal_link").withStyle(s -> s.withColor(ChatFormatting.AQUA)
                   .withUnderlined(true)
                   .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/liveappeal " + n))
                   .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(KEY + "appeal_link_hover")))));
        }
        return out;
    }

    // --- appeals ----------------------------------------------------------------------------------

    /** What the relay said to a {@code /liveappeal}, turned into the chat line the player hears. */
    static void onAppealResponse(String requestBody, int status, String responseBody) {
        if (status < 0) return; // never delivered; the outbox retries
        UUID player = LiveModerationClient.playerOf(requestBody);
        if (player == null) return;
        String key = appealLineKey(status, responseBody);
        if (key == null) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        server.execute(() -> {
            ServerPlayer online = server.getPlayerList().getPlayer(player);
            if (online != null) online.sendSystemMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY));
        });
    }

    /** The lang key for a relay answer to an appeal; null when there is nothing to say. Pure. */
    static String appealLineKey(int status, String responseBody) {
        if (status / 100 == 2) return "command.dungeontrain.live_appeal.received";
        String error = "";
        try {
            var o = com.google.gson.JsonParser.parseString(responseBody == null ? "" : responseBody).getAsJsonObject();
            if (o.has("error")) error = o.get("error").getAsString();
        } catch (Throwable t) {
            // fall through to the generic line
        }
        return switch (error) {
            case "not_banned" -> "command.dungeontrain.live_appeal.not_banned";
            case "appeal_pending" -> "command.dungeontrain.live_appeal.pending";
            case "appeal_denied" -> "command.dungeontrain.live_appeal.denied";
            default -> "command.dungeontrain.live_appeal.failed";
        };
    }
}
