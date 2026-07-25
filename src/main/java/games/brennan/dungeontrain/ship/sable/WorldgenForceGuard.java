package games.brennan.dungeontrain.ship.sable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Marks the current thread as being inside a Dungeon Train <b>synchronous forced chunk
 * generation</b> (a {@code level.getChunk(cx, cz, ChunkStatus.FULL, true)} on the server thread).
 *
 * <p><b>Why:</b> forcing a chunk to {@code FULL} on the server thread blocks the main thread in a
 * {@code ServerChunkCache.getChunk} managedBlock while worldgen runs. If the generated chunk
 * contains flowing water/lava, {@code postProcessGeneration} ticks the fluid and calls
 * {@code setBlock}. Sable intercepts that via its {@code LevelChunk.setBlockState} mixin →
 * {@code SableCommonEvents.handleBlockChange} → {@code SubLevelPhysicsSystem.handleBlockChange} →
 * {@code RapierPhysicsPipeline} → {@code VoxelNeighborhoodState.getState} →
 * {@code LevelAccelerator.getBlockState} → <b>getChunk again</b>, re-entering the managedBlock
 * <i>inside</i> the outer one → a permanent re-entrant deadlock (the spawn "fall forever, never
 * board the train" bug — the client's {@code CinematicPreloadGate} then times out and would
 * dump the player into the void).</p>
 *
 * <p>{@code SableBlockChangeGuardMixin} reads {@link #isActive()} at the head of
 * {@code handleBlockChange} and cancels the physics/mass update while the guard is set, so the
 * re-entrant {@code getChunk} never happens during our forced generation. The flag is a
 * <b>thread-local depth counter</b>: it is set only from server-thread forced-gen call sites, so
 * only that thread sees {@code isActive() == true}. Worldgen worker threads keep their own
 * (zero) count and are unaffected. Nested forced getChunk calls increment/decrement the depth so
 * the guard survives re-entry.</p>
 *
 * <p><b>Safety:</b> the guard is active only during DT's own synchronous forced generation — a
 * narrow window over terrain being generated. {@code handleBlockChange} already early-outs unless
 * the changed block belongs to a Sable sub-level chunk, so the only calls actually suppressed are
 * sub-level block changes happening <i>during</i> that forced gen — exactly the deadlock case.
 * Sub-level mass/physics is recomputed when the sub-level is assembled/loaded, so skipping the
 * transient worldgen-time update changes no simulated-ship state.</p>
 */
public final class WorldgenForceGuard {

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private WorldgenForceGuard() {}

    /** True while this thread is inside a DT forced-generation call (see class doc). */
    public static boolean isActive() {
        return DEPTH.get()[0] > 0;
    }

    /**
     * Force {@code (cx, cz)} to {@link ChunkStatus#FULL} synchronously with the guard held, so
     * Sable's block-change hook can't re-enter {@code getChunk} during the generation.
     * Drop-in replacement for {@code level.getChunk(cx, cz, ChunkStatus.FULL, true)}.
     */
    public static ChunkAccess forceChunk(ServerLevel level, int cx, int cz) {
        int[] depth = DEPTH.get();
        depth[0]++;
        try {
            return level.getChunk(cx, cz, ChunkStatus.FULL, true);
        } finally {
            depth[0]--;
        }
    }

    /**
     * Run {@code body} (typically a region-force loop of {@code getChunk(FULL, true)} calls) with
     * the guard held for its whole duration. Use for callers that force many chunks in one go.
     */
    public static void runForced(Runnable body) {
        int[] depth = DEPTH.get();
        depth[0]++;
        try {
            body.run();
        } finally {
            depth[0]--;
        }
    }
}
