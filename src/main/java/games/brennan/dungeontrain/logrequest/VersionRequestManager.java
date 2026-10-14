package games.brennan.dungeontrain.logrequest;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.event.ContentModeMirror;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.VersionQueryPacket;
import games.brennan.dungeontrain.net.relay.VersionReplyClient;
import games.brennan.dungeontrain.net.relay.VersionReplyClient.Status;
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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server side of the Developer {@code /version} ping. The relay owns the request: it fans a
 * Brennan-authored {@code /version [full] #<reqId>} out to the player's server, this asks the
 * player's client which version it runs ({@link VersionQueryPacket}), and the answer goes back to
 * the relay ({@link VersionReplyClient}), which shows it to Brennan alone.
 *
 * <p>Unlike {@code /bug} there is no consent prompt and nothing is shown in game; only version and
 * mod facts are read. Kid-mode players are never asked — Brennan hears "Kid mode is on".</p>
 *
 * <p>A {@code /version} with no relay id (an older relay passing a typed thread message through) is
 * swallowed so it never reaches the player as a dev message, and only logged.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class VersionRequestManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How long the client has to answer before Brennan hears what the server knows instead. */
    static final long ANSWER_WINDOW_MS = 10_000L;

    record Pending(int id, String reqId, long deadlineMs) {}

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    private VersionRequestManager() {}

    /** Brennan's {@code /version} reached the server for {@code owner}. Called on the Discord network thread. */
    public static void onCommand(@Nullable UUID owner, VersionReportText.Command cmd) {
        if (cmd.reqId() == null) {
            LOGGER.info("[DungeonTrain] /version without a relay request id ignored (relay too old?)");
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (owner == null || server == null) {
            if (owner != null) VersionReplyClient.post(cmd.reqId(), owner, "", Status.OFFLINE, "not in a world");
            return;
        }
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player == null) {
                VersionReplyClient.post(cmd.reqId(), owner, "", Status.OFFLINE, "not in a world");
                return;
            }
            ask(player, cmd);
        });
    }

    private static void ask(ServerPlayer player, VersionReportText.Command cmd) {
        if (ContentModeMirror.isKid(player)) {
            reply(player, cmd.reqId(), Status.KID, "Kid mode is on");
            return;
        }
        int id = NEXT_ID.getAndIncrement();
        Pending previous = PENDING.put(player.getUUID(),
                new Pending(id, cmd.reqId(), System.currentTimeMillis() + ANSWER_WINDOW_MS));
        if (previous != null) {
            reply(player, previous.reqId(), Status.TIMEOUT, "superseded by a newer /version");
        }
        DungeonTrainNet.sendTo(player, new VersionQueryPacket(id, cmd.full()));
        LOGGER.info("[DungeonTrain] Developer version ping #{} sent to {}", id, name(player));
    }

    /** The client answered request {@code id}. Unknown or stale ids are ignored. Server thread. */
    public static void onReport(ServerPlayer player, int id, String text) {
        Pending p = PENDING.get(player.getUUID());
        if (p == null || p.id() != id) return;
        PENDING.remove(player.getUUID());
        reply(player, p.reqId(), Status.OK, withServer(player.getServer(), VersionReportText.cap(text)));
    }

    /**
     * On a dedicated server the client's version can differ from the server's, so name both. The
     * integrated (single-player / LAN host) server is the same jar as the client.
     */
    private static String withServer(@Nullable MinecraftServer server, String text) {
        if (server == null || !server.isDedicatedServer()) return text;
        return text + "\nServer: " + VersionReportText.shortLine(VersionInfo.VERSION, VersionInfo.BRANCH);
    }

    /** Once a second: clients that never answered get the server's own version reported instead. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty() || event.getServer().getTickCount() % 20 != 0) return;
        long now = System.currentTimeMillis();
        PENDING.entrySet().removeIf(e -> {
            Pending p = e.getValue();
            if (now <= p.deadlineMs()) return false;
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(e.getKey());
            String text = "no answer from the game client — server is on "
                    + VersionReportText.shortLine(VersionInfo.VERSION, VersionInfo.BRANCH);
            VersionReplyClient.post(p.reqId(), e.getKey(), player == null ? "" : name(player), Status.TIMEOUT, text);
            return true;
        });
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        Pending p = PENDING.remove(event.getEntity().getUUID());
        if (p != null) {
            VersionReplyClient.post(p.reqId(), event.getEntity().getUUID(),
                    event.getEntity().getGameProfile().getName(), Status.LEFT, "left the world before answering");
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
    }

    private static void reply(ServerPlayer player, String reqId, Status status, String text) {
        VersionReplyClient.post(reqId, player.getUUID(), name(player), status, text);
        LOGGER.info("[DungeonTrain] Developer version ping for {}: {}", name(player), status);
    }

    private static String name(ServerPlayer player) {
        return player.getGameProfile().getName();
    }
}
