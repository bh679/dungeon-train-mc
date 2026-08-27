package games.brennan.dungeontrain.debug;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.TrainDebugSyncPacket;
import games.brennan.dungeontrain.net.relay.DebugGrantClient;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;
import java.util.UUID;

/**
 * Keeps every player's debug-panel access in step with the relay, and tells their client what it
 * currently is.
 *
 * <p>Three things drive a re-sync:</p>
 * <ul>
 *   <li><b>Join</b> — {@link #onPlayerLogin} sends what the cache already knows (so a granted
 *       player has the panel immediately, even with the relay down) and kicks off a relay fetch to
 *       correct it.</li>
 *   <li><b>Poll</b> — every {@value #POLL_INTERVAL_TICKS} ticks one relay fetch per online player,
 *       so a grant issued or <em>revoked</em> relay-side lands on a running server without anyone
 *       having to rejoin.</li>
 *   <li><b>Sweep</b> — every {@value #SWEEP_INTERVAL_TICKS} ticks, lapsed grants are dropped and
 *       those clients told to close the panel. Short enough that the 5-minute block ends when it
 *       says it does.</li>
 * </ul>
 *
 * <p>Grants live in the world's saved data ({@link DungeonTrainWorldData#debugGrants()}), keyed on
 * the overworld like every other DT world state.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DebugAccessEvents {

    /** 5 minutes. A grant issued relay-side shows up within one of these. */
    private static final int POLL_INTERVAL_TICKS = 20 * 60 * 5;
    /** 30 seconds — comfortably finer than the shortest (5-minute) grant block. */
    private static final int SWEEP_INTERVAL_TICKS = 20 * 30;

    private static int tick = 0;

    private DebugAccessEvents() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        tick++;
        if (tick % SWEEP_INTERVAL_TICKS == 0) {
            sweep(server);
        }
        if (tick % POLL_INTERVAL_TICKS == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                refreshFromRelay(player);
            }
        }
    }

    /**
     * Send the cached verdict now, then ask the relay. Sending first is what makes a grant survive
     * a relay outage all the way to the player's screen rather than only in the save file.
     */
    public static void onPlayerLogin(ServerPlayer player) {
        syncTo(player);
        refreshFromRelay(player);
    }

    /** Push this player's current access (and, only if granted, the world's train seed). */
    public static void syncTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(server.overworld());
        DebugAccessGrants.Grant grant = data.debugGrants().grantFor(player.getUUID());
        if (grant == null) {
            DungeonTrainNet.sendTo(player, TrainDebugSyncPacket.denied());
            return;
        }
        DungeonTrainNet.sendTo(player, new TrainDebugSyncPacket(
            true, grant.expiresAtMs(), data.getGenerationConfig().seed()));
    }

    /**
     * Ask the relay about one player and apply the answer on the server thread. A failed fetch
     * never calls back, so the cached grant stands.
     */
    private static void refreshFromRelay(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        UUID uuid = player.getUUID();
        DebugGrantClient.fetch(uuid, grant -> server.execute(() -> applyGrant(server, uuid, grant)));
    }

    /** Server-thread: fold a relay answer into the cache and re-sync the player if it changed. */
    private static void applyGrant(MinecraftServer server, UUID uuid, DebugAccessGrants.Grant grant) {
        ServerLevel overworld = server.overworld();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.debugGrants().apply(uuid, grant)) {
            return;
        }
        data.markDebugGrantsDirty();
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player != null) {
            syncTo(player);
        }
    }

    /** Drop lapsed grants and close the panel under anyone who just lost one. */
    private static void sweep(MinecraftServer server) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(server.overworld());
        List<UUID> lapsed = data.debugGrants().sweepExpired();
        if (lapsed.isEmpty()) return;
        data.markDebugGrantsDirty();
        for (UUID uuid : lapsed) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                DungeonTrainNet.sendTo(player, TrainDebugSyncPacket.denied());
            }
        }
    }
}
