package games.brennan.dungeontrain.train;

import net.minecraft.world.level.block.Block;

/**
 * Marks the current thread as being inside a Dungeon Train <b>system block write</b> — a template
 * being stamped into the world, whether for a spawning train carriage (place, Sable lift, contents),
 * an editor preview plot, or a loader restoring a saved carriage, tunnel, track or pillar — and the
 * editor's display-only rewrites of a plot: the variant preview ticker, menu previews, the variant
 * mirror's cosmetic stamp, and the reset / delete-and-restamp commands.
 *
 * <p><b>Consumers.</b> {@code CropBlockCarriageSurviveMixin} relaxes the crop light check while the
 * guard is held (the original reason for it, below), and {@code ObserverBlockStampMixin} keeps
 * observers from pulsing at our own placement — only a player or a gameplay cause should fire one.
 * Any loader that writes a template with a cascading flag belongs inside this guard.</p>
 *
 * <p><b>Why:</b> a template is authoritative. Whatever the author saved into the {@code .nbt} is what
 * should stand in the carriage. But a carriage is written a cell at a time across several passes, and
 * vanilla blocks that check their surroundings ({@code BlockBehaviour.canSurvive}) see those
 * half-finished intermediate states and delete themselves. Crops are the case that bit us:
 * {@code CropBlock.canSurvive} demands {@code getRawBrightness(pos, 0) >= 8}, and
 * {@code BushBlock.updateShape} replaces the crop with air the moment that fails. The shell is stamped
 * section-local ({@code relight=false}), so the light engine has not yet processed the carriage's own
 * lanterns when the very next pass — {@code applyVariantBlocks}, writing with
 * {@code UPDATE_CLIENTS | UPDATE_SUPPRESS_DROPS} — runs the neighbour-shape cascade over them. The
 * interior reads dark at that instant and every crop in it pops. That is not a real gameplay state; it
 * is our own construction scaffolding, visible to nobody.</p>
 *
 * <p><b>Scope.</b> This guard exists for stamps happening at <b>ordinary world coordinates</b>, which
 * is the one window {@code CropBlockCarriageSurviveMixin}'s shipyard-coordinate test cannot see:
 * the pre-lift spawn stamp in the source world, and the editor preview plots (which sit near the
 * origin at y≈250 and are never lifted at all — without this, a saved wheat template came back empty
 * the next time its author opened it). Everything at shipyard coordinates — the Sable lift and the
 * carriage's entire subsequent life — is covered by the position test instead and needs no guard.
 * Note the lift is <i>not</i> a soil-ordering hazard: {@code moveBlocks} writes every destination cell
 * before it cascades over any of them, so the farmland is always already there. See that mixin's
 * javadoc for the bytecode reference.</p>
 *
 * <p><b>Safety.</b> The flag is a <b>thread-local depth counter</b>, mirroring
 * {@link games.brennan.dungeontrain.ship.sable.WorldgenForceGuard}. It is set only from server-thread
 * placement call sites, so only that thread ever sees {@code isActive() == true}; worldgen workers and
 * the client keep their own (zero) count. The counter rather than a boolean is what makes re-entry
 * correct: {@code TrainAssembler.spawnGroup} holds the guard and then calls
 * {@code CarriagePlacer.placeAt}, which holds it again. Every acquire/release pair is a
 * {@code try/finally}, and callers must go through {@link #run} / {@link #call} rather than touch the
 * counter directly — a stuck flag would suppress the crop light check for every crop on the server
 * thread for the rest of the process's life, which is the one catastrophic failure mode here.</p>
 */
public final class CarriageStampGuard {

    /**
     * The flags every Dungeon Train template {@code placeInWorld} passes — {@link Block#UPDATE_CLIENTS}
     * (flag 2), <b>never</b> {@link Block#UPDATE_ALL} (flag 3, the value vanilla structure blocks and
     * {@code /place} use). This is the single owner of that choice; a source-scan unit test
     * ({@code StampFlagsSourceScanTest}) fails the build on any {@code placeInWorld} that passes
     * anything else.
     *
     * <p><b>Why not flag 3.</b> {@code UPDATE_ALL} fires {@code neighborChanged} on every cell already
     * down each time the next one lands, and Fast Paintings answers that with {@code canSurvive}, which
     * wants the picture's master block entity AND every one of its cells present already. Half-way
     * through a 3×2 picture that is false, the mod removes the whole group and drops the item — every
     * picture in a relay build popped on load until #1451 moved the loaders to flag 2. With flag 2 the
     * neighbour cascade still happens, once, in {@code placeInWorld}'s own final pass, after every cell
     * and its NBT are in. The play-side stamps ({@code TrackGenerator}, {@code TunnelPlacer}) had
     * always used flag 2 for the same reason, which is why they kept their pictures.</p>
     *
     * <p>Most stamps should go through {@link games.brennan.dungeontrain.template.TemplateStamp}, which
     * pairs this flag with the guard (and, optionally, the {@code TemplateDecor} pass). Sites that need
     * their own settings or wrap more work inside the guard reference the constant directly.</p>
     */
    public static final int STAMP_FLAGS = Block.UPDATE_CLIENTS;

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
