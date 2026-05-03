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
 * <p>The five wireframe flags expose what was previously a single
 * {@code wireframes} switch as individual toggles — they serve different
 * debugging workflows (e.g. collision-only vs. gap-tuning) so the player
 * wants to enable one at a time rather than pay the visual cost of all
 * overlays at once. The {@code Wireframes} sub-menu (X menu → Debug →
 * Wireframes) provides an "All On / All Off" master alongside the per-flag
 * toggles.</p>
 *
 * <p>{@link #manualSpawnMode()} delegates to
 * {@link TrainCarriageAppender#MANUAL_MODE} (the appender owns the flag so
 * the spawn loop can read it without depending on this debug package).</p>
 */
public final class DebugFlags {

    private static volatile boolean gapCubes = false;
    private static volatile boolean gapLine = false;
    private static volatile boolean nextSpawn = false;
    private static volatile boolean collision = false;
    private static volatile boolean hudDistance = false;

    private DebugFlags() {}

    public static boolean gapCubes() { return gapCubes; }
    public static boolean gapLine() { return gapLine; }
    public static boolean nextSpawn() { return nextSpawn; }
    public static boolean collision() { return collision; }
    public static boolean hudDistance() { return hudDistance; }

    public static boolean manualSpawnMode() {
        return TrainCarriageAppender.MANUAL_MODE;
    }

    public static void setGapCubes(MinecraftServer server, boolean value) {
        gapCubes = value;
        broadcastTo(server);
    }

    public static void setGapLine(MinecraftServer server, boolean value) {
        gapLine = value;
        broadcastTo(server);
    }

    public static void setNextSpawn(MinecraftServer server, boolean value) {
        nextSpawn = value;
        broadcastTo(server);
    }

    public static void setCollision(MinecraftServer server, boolean value) {
        collision = value;
        broadcastTo(server);
    }

    public static void setHudDistance(MinecraftServer server, boolean value) {
        hudDistance = value;
        broadcastTo(server);
    }

    /** Master setter — flips all five wireframe flags to {@code value} in one broadcast. */
    public static void setAllWireframes(MinecraftServer server, boolean value) {
        gapCubes = value;
        gapLine = value;
        nextSpawn = value;
        collision = value;
        hudDistance = value;
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
        return new DebugFlagsPacket(
            gapCubes, gapLine, nextSpawn, collision, hudDistance,
            TrainCarriageAppender.MANUAL_MODE
        );
    }
}
