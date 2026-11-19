package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.block.flag.FlagRoll;
import games.brennan.dungeontrain.block.stage.StagePlaceholderBlocks;
import games.brennan.dungeontrain.provenance.ProvenanceScope;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.ThreadLocalRandom;
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
        /** The room's own flag roll when the caller names one (portal rooms and their copies). */
        final OptionalLong roomFlagKey;
        /** The flag roll for a scope with neither a room key nor a train carriage in it. */
        final long flagKey = ThreadLocalRandom.current().nextLong();
        int swapped;
        int flags;
        /** Carriage the last flag was rolled for, or {@code "-"} outside a carriage stamp. */
        String flagCarriage = "-";

        Scope(String stageId, OptionalLong roomFlagKey) {
            this.stageId = stageId;
            this.roomFlagKey = roomFlagKey;
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
     * The flag roll key for this placement, or empty when no placement is in scope (an editor
     * stamp, whose flags must stay flags). A room key the caller named wins (a portal room and its
     * copies, stamped at different times, show the same flags); inside a train carriage's stamp
     * the key is the carriage's own ({@link FlagRoll#carriageKey}), so its shell, parts, contents
     * and variant cells, stamped in separate scopes, all match; anywhere else it is the scope's
     * own random key.
     */
    public static OptionalLong flagRollKey(long worldSeed) {
        Scope s = CURRENT.get();
        if (s == null) return OptionalLong.empty();
        if (s.roomFlagKey.isPresent()) return s.roomFlagKey;
        OptionalInt carriage = ProvenanceScope.current();
        return OptionalLong.of(carriage.isPresent()
            ? FlagRoll.carriageKey(worldSeed, carriage.getAsInt())
            : s.flagKey);
    }

    /** Count one flag placeholder swapped for its banner in the current scope (for the log line). */
    public static void noteFlagResolved() {
        Scope s = CURRENT.get();
        if (s == null) return;
        s.flags++;
        OptionalInt carriage = ProvenanceScope.current();
        s.flagCarriage = carriage.isPresent() ? Integer.toString(carriage.getAsInt()) : "-";
    }

    /**
     * Run {@code body} with {@code stageId} (nullable ⇒ default palette) in scope. A nested scope
     * keeps its own count; the outer one resumes after. One INFO line per scope that swapped
     * anything — the evidence a build's placeholders resolved, and for which stage.
     */
    public static <T> T with(String stageId, Supplier<T> body) {
        return with(stageId, OptionalLong.empty(), body);
    }

    /**
     * {@link #with(String, Supplier)} with the room's own flag roll key ({@link FlagRoll#roomKey}),
     * for rooms stamped in several passes outside a carriage (portal rooms and their copies).
     */
    public static <T> T with(String stageId, OptionalLong roomFlagKey, Supplier<T> body) {
        Scope prev = CURRENT.get();
        Scope scope = new Scope(stageId, roomFlagKey);
        CURRENT.set(scope);
        try {
            return body.get();
        } finally {
            if (prev == null) CURRENT.remove(); else CURRENT.set(prev);
            if (scope.swapped > 0) {
                LOGGER.info("[DungeonTrain] Stage placeholders: resolved {} cell(s) for stage '{}'.",
                    scope.swapped, stageId == null ? "<default>" : stageId);
            }
            if (scope.flags > 0) {
                LOGGER.info("[DungeonTrain] Flags: rolled {} flag(s) (carriage {}).",
                    scope.flags, scope.flagCarriage);
            }
        }
    }

    /** {@link #with(String, Supplier)} for a void body. */
    public static void run(String stageId, Runnable body) {
        with(stageId, () -> { body.run(); return null; });
    }

    /** {@link #with(String, OptionalLong, Supplier)} for a void body. */
    public static void run(String stageId, OptionalLong roomFlagKey, Runnable body) {
        with(stageId, roomFlagKey, () -> { body.run(); return null; });
    }
}
