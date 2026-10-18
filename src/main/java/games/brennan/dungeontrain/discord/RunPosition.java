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
    static final RunPosition NONE = new RunPosition(null, null, null);

    /**
     * How far a life's forward displacement may run ahead of its boarded odometer
     * ({@code PlayerRunState#distanceBlocks}) and still be believed. Legit displacement can exceed the
     * odometer: it books only boarded movement and drops any single jump of 100+ blocks, so the one
     * forward portal trip (first overworld to the first Nether core, {@code NetherPortalJump}) and
     * walking off the train never reach it. This covers both with room to spare; the plot-grid
     * teleport of #2080 (X ~20.48M) was hundreds of times past it.
     */
    static final long ODOMETER_SLACK_BLOCKS = 50_000L;

    /**
     * Resolve the position fields for {@code player} at their current location. Never throws: any
     * failure degrades to omitted fields rather than disrupting death handling.
     *
     * <p>Resolve this ONCE per death and hand the result around — {@code RunStatsEvents} does, and
     * both the reporters and the lifetime displacement counter read it. It is only valid before
     * respawn, which re-stamps the origin this is measured from.</p>
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
     * This life's forward progress as a leaderboard score: the displacement, floored at zero. A run
     * that ended behind where it started, or one whose origin was never captured, contributes
     * nothing — it does not eat into a lifetime total that only ever goes up.
     */
    public long forwardMetres() {
        return distanceTravelled == null ? 0L : Math.max(0L, distanceTravelled.longValue());
    }

    /**
     * Whether this life's displacement can be trusted as a score. A life that ended inside the Sable
     * plot grid ended at a ship-plot coordinate, not a world one, so its X says nothing about how far
     * the player got (#2080). A displacement far beyond what the odometer and one portal trip allow is
     * just as wrong. Pure, so the rule is testable without the game.
     */
    static boolean isPlausible(boolean endInPlotGrid, long forwardMetres, double odometerBlocks) {
        if (endInPlotGrid) return false;
        double odometer = Double.isFinite(odometerBlocks) ? Math.max(0.0, odometerBlocks) : 0.0;
        return forwardMetres <= odometer + ODOMETER_SLACK_BLOCKS;
    }

    /**
     * This position, or {@link #NONE} when {@link #isPlausible} rejects it. Applied once at death so the
     * lifetime displacement counter and every reporter drop the same bad life together: nothing is
     * added to the total, and the payloads omit {@code spawnX}, {@code distanceTravelled} and
     * {@code band} exactly as for a life whose origin was never captured.
     */
    public RunPosition screened(boolean endInPlotGrid, double odometerBlocks) {
        return isPlausible(endInPlotGrid, forwardMetres(), odometerBlocks) ? this : NONE;
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
