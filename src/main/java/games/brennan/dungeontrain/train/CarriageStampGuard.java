package games.brennan.dungeontrain.train;

/**
 * Marks the current thread as being inside a Dungeon Train <b>carriage stamp or lift</b> — the
 * window that runs from the first template write, through the Sable assemble, to the end of the
 * contents pass.
 *
 * <p><b>Why:</b> a template is authoritative. Whatever the author saved into the {@code .nbt} is
 * what should stand in the carriage. But the placement sequence writes a carriage's cells one at a
 * time, through several passes with different flags, and vanilla blocks that check their own
 * surroundings ({@code BlockBehaviour.canSurvive}) see those half-finished intermediate states and
 * delete themselves. Crops are the case that bit us: {@code CropBlock.canSurvive} demands farmland
 * below <em>and</em> {@code getRawBrightness(pos, 0) >= 8}, and {@code BushBlock.updateShape}
 * replaces the crop with air the moment either fails. Two distinct moments during placement fail
 * it:</p>
 *
 * <ol>
 *   <li><b>Source world, pre-lift.</b> {@link CarriagePlacer#placeAt} stamps the shell and parts
 *       section-local ({@code relight=false}), so the light engine never processes the carriage's
 *       own lanterns; the very next pass, {@code applyVariantBlocks}, writes with
 *       {@code UPDATE_CLIENTS | UPDATE_SUPPRESS_DROPS}, which <em>does</em> run the neighbour-shape
 *       cascade. The interior reads dark at that instant and every crop in it pops. (Which is why
 *       the bug looked intermittent: a carriage spawning under open daylight passed the light check
 *       and survived; one spawning in a tunnel or at night did not.)</li>
 *   <li><b>The Sable lift.</b> {@code TrainAssembler.spawnGroup} calls {@code assemble}, and Sable's
 *       {@code moveBlocks} re-writes every block through {@code LevelChunk.setBlockState} with
 *       {@code markAndNotifyBlock(..., 3, 512)} — a flag-3 cascade per block, one block at a time.
 *       Here a crop can fail on <em>soil</em> as well as light, if it happens to be moved before the
 *       farmland beneath it has arrived.</li>
 * </ol>
 *
 * <p>Neither window is a real gameplay event: they are our own construction scaffolding, visible to
 * nobody. {@code CropBlockCarriageSurviveMixin} reads {@link #isActive()} and lets a crop survive
 * unconditionally while the flag is held, so a cell the author saved cannot be deleted by a state
 * the carriage passes through on its way to being finished. Once the guard drops, vanilla rules
 * resume — see that mixin for the separate, permanent shipyard rule that outlives placement.</p>
 *
 * <p><b>Scope and safety.</b> The flag is a <b>thread-local depth counter</b>, mirroring
 * {@link games.brennan.dungeontrain.ship.sable.WorldgenForceGuard}. It is set only from server-thread
 * placement call sites, so only that thread ever sees {@code isActive() == true}; worldgen workers
 * and the client keep their own (zero) count and are unaffected. The guard is held at two nesting
 * levels — around {@code spawnGroup}'s whole place/assemble/contents sequence and again inside
 * {@code placeAt} for callers that stamp a carriage on their own — so the counter, rather than a
 * boolean, is what makes re-entry correct. Every acquire/release pair is a {@code try/finally}: an
 * exception mid-stamp must not leave the flag stuck on, because a stuck flag would suppress crop
 * survival checks for the rest of the server's life.</p>
 */
public final class CarriageStampGuard {

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private CarriageStampGuard() {}

    /** True while this thread is inside a DT carriage stamp/lift (see class doc). */
    public static boolean isActive() {
        return DEPTH.get()[0] > 0;
    }

    /** Run {@code body} with the guard held for its whole duration. */
    public static void run(Runnable body) {
        int[] depth = DEPTH.get();
        depth[0]++;
        try {
            body.run();
        } finally {
            depth[0]--;
        }
    }

    /**
     * {@link #run(Runnable)} for a body that returns a value — the common shape here, since the
     * placement methods return the block set they wrote.
     */
    public static <T> T call(java.util.function.Supplier<T> body) {
        int[] depth = DEPTH.get();
        depth[0]++;
        try {
            return body.get();
        } finally {
            depth[0]--;
        }
    }
}
