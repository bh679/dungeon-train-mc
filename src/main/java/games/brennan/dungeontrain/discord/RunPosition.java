package games.brennan.dungeontrain.discord;

import games.brennan.dungeontrain.event.BoardingProgressEvents;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

/**
 * The positional half of a per-life telemetry payload, resolved once and shared by
 * {@link DeathReporter} and {@link RunSummaryReporter} so the two can never disagree about where a
 * life ended.
 *
 * <p>Exists because the relay cannot derive any of this itself. {@code distanceTravelled} is a
 * <b>signed X-axis displacement</b> ({@code endX - spawnX}) — deliberately not the
 * {@code distanceBlocks} odometer, which is a 3D path length inflated by walking laps on a carriage
 * and so says nothing about how far down the line a player actually got. {@code band} is resolved
 * server-side from the live worldgen config; deriving it relay-side would mean replicating
 * {@code WorldGenCycle} geometry (segment lengths, {@code startX}, {@code phaseShift}, enable flags)
 * that never leaves the server, and would silently mislabel any non-default world.</p>
 *
 * <p>{@code spawnX} rides along so the relay can reconstruct the absolute world-X
 * ({@code spawnX + distanceTravelled}) that {@code band} was computed from.</p>
 *
 * @param spawnX            world-X this life began at, or {@code null} if never captured
 * @param distanceTravelled signed X displacement from {@code spawnX}, or {@code null} with it
 * @param band              {@link TrainPhase#token()} at the end position, or {@code null} if
 *                          the overworld was unavailable
 */
public record RunPosition(@Nullable Integer spawnX,
                          @Nullable Integer distanceTravelled,
                          @Nullable String band) {

    /** Empty result — every field omitted from the payload. */
    private static final RunPosition NONE = new RunPosition(null, null, null);

    /**
     * Resolve the position fields for {@code player} at their current location. Never throws: any
     * failure degrades to omitted fields rather than disrupting death handling.
     */
    public static RunPosition of(ServerPlayer player) {
        try {
            int endX = player.blockPosition().getX();
            Integer spawnX = BoardingProgressEvents.runSpawnX(player);
            Integer travelled = spawnX == null ? null : endX - spawnX;
            return new RunPosition(spawnX, travelled, bandAt(player, endX));
        } catch (Throwable t) {
            return NONE;
        }
    }

    /**
     * Band token at {@code worldX}. Always classified against the <b>overworld</b> — the bands are a
     * property of the train's X progression, so a player who died in another dimension still belongs
     * to the band their train had reached.
     */
    @Nullable
    private static String bandAt(ServerPlayer player, int worldX) {
        MinecraftServer server = player.getServer();
        if (server == null) return null;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return null;
        TrainPhase phase = TrainPhase.phaseAt(overworld, worldX);
        return phase == null ? null : phase.token();
    }
}
