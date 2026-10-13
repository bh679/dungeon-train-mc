package games.brennan.dungeontrain.logrequest;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.compat.DiscordCommandHooks;
import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.ContentModeMirror;
import games.brennan.dungeontrain.event.DevMessageConsent;
import games.brennan.dungeontrain.logrequest.DeliveryFailureReport.Reason;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.LogRequestPromptPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server side of the Developer log request: Brennan types {@code /bug} in a player's Discord thread
 * (or the livestream chat), the player is asked in game whether to send their logs, and on yes the
 * normal bug-report upload runs and Brennan is tagged.
 *
 * <p>One request is pending per player at a time, identified by a server-issued id; the client
 * answers that id ({@link Answer}), and an upload labelled {@link #UPLOAD_LABEL} is only accepted
 * (and only pings Brennan) while a SEND answer for it is outstanding. Every way a request can fail to
 * land — Kid mode, Developer chat off, no answer, declined — is reported back to Brennan through
 * {@link DeliveryFailureReport}.</p>
 *
 * <p>Threading: the Discord seam fires on a network thread and hops to the server thread; every
 * other entry point already runs there. Maps are concurrent as cheap insurance.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LogRequestManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The bug-report label an approved upload carries; it still starts the relay-archived "🐛 Bug report" post. */
    public static final String UPLOAD_LABEL = "Developer request";
    /** How long the player has to answer before Brennan hears "didn't answer in time". */
    static final long ANSWER_WINDOW_MS = 10L * 60_000L;
    /** How long, after a SEND, the logs have to arrive (collection runs on a worker thread). */
    static final long UPLOAD_WINDOW_MS = 2L * 60_000L;
    /** Livestream requests are relayed by the client, so they are rate-limited per player. */
    static final long LIVE_GAP_MS = 60_000L;

    /** Where a request came from. */
    public enum Origin { THREAD, LIVE }

    /** The client's answer to a request. */
    public enum Answer { SEND, DECLINE, BLOCKED }

    /** An outstanding request. {@code sending} = the player said yes and the logs are on their way. */
    record Pending(int id, Origin origin, long deadlineMs, boolean sending,
                   @Nullable DiscordCommandHooks.Reply reply) {
        Pending sendingUntil(long deadline) {
            return new Pending(id, origin, deadline, true, reply);
        }
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_LIVE_MS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    private LogRequestManager() {}

    /** Whether {@code content} is the log-request command ({@code /bug} or {@code !bug}). */
    public static boolean isCommand(@Nullable String content) {
        if (content == null) return false;
        String c = content.trim().toLowerCase(Locale.ROOT);
        return c.equals("/bug") || c.equals("!bug");
    }

    /**
     * Brennan typed {@code /bug} in the thread of {@code owner}. Called on the Discord network thread.
     * With no world loaded nothing is in game to ask, so Brennan is told so straight away.
     */
    public static void onThreadCommand(@Nullable UUID owner, DiscordCommandHooks.Reply reply) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (owner == null || server == null) {
            DeliveryFailureReport.report(reply, null, null, Reason.NOT_IN_WORLD);
            return;
        }
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player == null) {
                DeliveryFailureReport.report(reply, null, null, Reason.NOT_IN_WORLD);
                return;
            }
            request(player, Origin.THREAD, reply);
        });
    }

    /** The player's client saw a livestream-chat request aimed at them. Server thread. */
    public static void onLiveRequest(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long last = LAST_LIVE_MS.get(player.getUUID());
        if (last != null && now - last < LIVE_GAP_MS) {
            DeliveryFailureReport.report(null, player, name(player), Reason.TOO_SOON);
            return;
        }
        LAST_LIVE_MS.put(player.getUUID(), now);
        request(player, Origin.LIVE, null);
    }

    private static void request(ServerPlayer player, Origin origin, @Nullable DiscordCommandHooks.Reply reply) {
        Reason blocked = blockReason(ContentModeMirror.isKid(player),
                DevMessageConsent.isDevChatOff(player.getUUID()),
                PENDING.containsKey(player.getUUID()));
        if (blocked != null) {
            DeliveryFailureReport.report(reply, player, name(player), blocked);
            return;
        }
        int id = NEXT_ID.getAndIncrement();
        PENDING.put(player.getUUID(),
                new Pending(id, origin, System.currentTimeMillis() + ANSWER_WINDOW_MS, false, reply));
        DungeonTrainNet.sendTo(player, new LogRequestPromptPacket(id));
        LOGGER.info("[DungeonTrain] Developer log request #{} ({}) sent to {}", id, origin, name(player));
    }

    /** Why a request can't be shown, or null when it can. Package-private for unit tests. */
    @Nullable
    static Reason blockReason(boolean kid, boolean devChatOff, boolean alreadyPending) {
        if (kid) return Reason.KID_MODE;
        if (devChatOff) return Reason.DEV_CHAT_OFF;
        if (alreadyPending) return Reason.ALREADY_PENDING;
        return null;
    }

    /** The client answered request {@code id}. Unknown or stale ids are ignored. Server thread. */
    public static void onAnswer(ServerPlayer player, int id, Answer answer) {
        Pending p = PENDING.get(player.getUUID());
        if (p == null || p.id() != id || p.sending()) return;
        switch (answer) {
            case SEND -> PENDING.put(player.getUUID(), p.sendingUntil(System.currentTimeMillis() + UPLOAD_WINDOW_MS));
            case DECLINE -> {
                PENDING.remove(player.getUUID());
                DeliveryFailureReport.report(p.reply(), player, name(player), Reason.DECLINED);
            }
            case BLOCKED -> {
                PENDING.remove(player.getUUID());
                DeliveryFailureReport.report(p.reply(), player, name(player), Reason.DEV_CHAT_OFF);
            }
        }
    }

    /**
     * A bug-report upload labelled {@link #UPLOAD_LABEL} arrived: true (and the request is closed) only
     * when it answers an outstanding SEND, so a client can't post unrequested "developer" uploads.
     */
    public static boolean consumeUpload(ServerPlayer player) {
        Pending p = PENDING.get(player.getUUID());
        if (p == null || !p.sending() || System.currentTimeMillis() > p.deadlineMs()) return false;
        PENDING.remove(player.getUUID());
        return true;
    }

    /** After an approved upload is posted: tag Brennan in the player's thread. */
    public static void pingDeveloper(ServerPlayer player) {
        try {
            DiscordService.get().postSurveyResponse(player,
                    "📎 Logs sent at your request — " + name(player),
                    "They approved the developer log request; the files are in the bug report above.",
                    List.of(), List.of(DungeonTrain.BRENNAN_DISCORD_ID));
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] developer log-request ping failed: {}", t.toString());
        }
    }

    /** Once a second: requests nobody answered (or whose logs never came) are reported and dropped. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty() || event.getServer().getTickCount() % 20 != 0) return;
        long now = System.currentTimeMillis();
        PENDING.entrySet().removeIf(e -> {
            Pending p = e.getValue();
            if (now <= p.deadlineMs()) return false;
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(e.getKey());
            if (!p.sending()) {
                DeliveryFailureReport.report(p.reply(), player,
                        player == null ? null : name(player), Reason.NO_ANSWER);
            }
            return true;
        });
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        Pending p = PENDING.remove(event.getEntity().getUUID());
        if (p != null && !p.sending()) {
            DeliveryFailureReport.report(p.reply(), null, event.getEntity().getGameProfile().getName(),
                    Reason.NOT_IN_WORLD);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
        LAST_LIVE_MS.clear();
    }

    private static String name(ServerPlayer player) {
        return player.getGameProfile().getName();
    }
}
