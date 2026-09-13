package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.block.stage.StagePlaceholderBlocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * The stage a placement is happening <em>for</em> — read by {@link StagePlaceholderProcessor} and
 * the variant-sidecar appliers to swap stage placeholder blocks for that stage's real blocks.
 *
 * <p>A thread-local rather than a new parameter on every stamp signature: the stamp chain is
 * {@code placeAt → stampBase/stampPartsOverlay → CarriagePartPlacer → stampTemplate*}, and all of
 * it runs on the thread that entered the scope (worldgen threading under C2ME still keeps one
 * placement on one thread).</p>
 *
 * <p>Resolution rules, in {@link #resolve}: inside a scope, placeholders resolve through the scoped
 * stage's palette — or {@link games.brennan.dungeontrain.template.StagePalette#DEFAULT} when the
 * scope has no stage (no stage claims that level/phase) — so a placeholder never reaches a live
 * train. <b>Outside any scope placeholders are left untouched.</b> That is deliberate: every editor
 * stamp (carriage / part / contents / room plots) runs unscoped, because those plots are captured
 * back into the template on save and a swapped block would silently overwrite the builder's
 * placeholder. Generation entry points opt in explicitly ({@code CarriagePlacer.placeAt} spawn path,
 * {@code applyContentsBlocksAt}, the portal room stamps).</p>
 */
public final class StagePlacementScope {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One entered scope: the stage (null ⇒ default palette) + how many cells it has swapped. */
    private static final class Scope {
        final String stageId;
        int swapped;

        Scope(String stageId) {
            this.stageId = stageId;
        }
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private StagePlacementScope() {}

    /** True when a placement scope is active on this thread (with or without a stage). */
    public static boolean active() {
        return CURRENT.get() != null;
    }

    /** The stage id in scope on this thread, or {@code null} (unscoped or no stage). */
    public static String current() {
        Scope s = CURRENT.get();
        return s == null ? null : s.stageId;
    }

    /** {@code state} swapped for the in-scope stage's real block, per the class rules. */
    public static BlockState resolve(BlockState state) {
        Scope s = CURRENT.get();
        if (s == null || !StagePlaceholderBlocks.isPlaceholder(state)) return state;
        BlockState out = StagePlaceholderBlocks.resolve(state, s.stageId);
        if (out != state) s.swapped++;
        return out;
    }

    /**
     * Run {@code body} with {@code stageId} (nullable ⇒ default palette) in scope. A nested scope
     * keeps its own count; the outer one resumes after. One INFO line per scope that swapped
     * anything — the evidence a build's placeholders resolved, and for which stage.
     */
    public static <T> T with(String stageId, Supplier<T> body) {
        Scope prev = CURRENT.get();
        Scope scope = new Scope(stageId);
        CURRENT.set(scope);
        try {
            return body.get();
        } finally {
            if (prev == null) CURRENT.remove(); else CURRENT.set(prev);
            if (scope.swapped > 0) {
                LOGGER.info("[DungeonTrain] Stage placeholders: resolved {} cell(s) for stage '{}'.",
                    scope.swapped, stageId == null ? "<default>" : stageId);
            }
        }
    }

    /** {@link #with(String, Supplier)} for a void body. */
    public static void run(String stageId, Runnable body) {
        with(stageId, () -> { body.run(); return null; });
    }
}
