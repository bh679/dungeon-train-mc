package games.brennan.dungeontrain.debug;

import games.brennan.dungeontrain.net.DebugFlagsPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-side toggle hub for in-world debugging overlays. All flags default
 * off (production-style behaviour); the in-world Debug menu (or its
 * underlying {@code /dungeontrain debug ...} commands) flips them on for a
 * debugging session and auto-broadcasts the new state to every player so
 * client renderers and the HUD pick it up immediately.
 *
 * <p>The two flags here intentionally collapse what would otherwise be
 * multiple finer-grained switches (gap line, ghost cubes, precise-length
 * bar, planned-spawn wireframe, HUD second line) into a single
 * {@code wireframes} toggle — they all serve the same debugging purpose
 * and the user wants one switch.</p>
 *
 * <p>{@link #manualSpawnMode()} delegates to
 * {@link TrainCarriageAppender#MANUAL_MODE} (the appender owns the flag so
 * the spawn loop can read it without depending on this debug package).</p>
 */
public final class DebugFlags {

    private static volatile boolean wireframesEnabled = false;

    private DebugFlags() {}

    public static boolean wireframesEnabled() {
        return wireframesEnabled;
    }

    public static boolean manualSpawnMode() {
        return TrainCarriageAppender.MANUAL_MODE;
    }

    /** Toggle wireframes server-side and broadcast to all connected clients. */
    public static void setWireframesEnabled(MinecraftServer server, boolean value) {
        wireframesEnabled = value;
        broadcastTo(server);
    }

    /** Toggle manual-spawn mode server-side and broadcast to all connected clients. */
    public static void setManualSpawnMode(MinecraftServer server, boolean value) {
        TrainCarriageAppender.MANUAL_MODE = value;
        broadcastTo(server);
    }

    /**
     * Send the current snapshot to one player — used on join to seed the
     * client cache before any toggle event fires.
     */
    public static void sendSnapshotTo(ServerPlayer player) {
        DungeonTrainNet.sendTo(player, snapshotPacket());
    }

    private static void broadcastTo(MinecraftServer server) {
        if (server == null) return;
        DebugFlagsPacket packet = snapshotPacket();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            DungeonTrainNet.sendTo(player, packet);
        }
    }

    private static DebugFlagsPacket snapshotPacket() {
        return new DebugFlagsPacket(wireframesEnabled, TrainCarriageAppender.MANUAL_MODE);
    }
}
