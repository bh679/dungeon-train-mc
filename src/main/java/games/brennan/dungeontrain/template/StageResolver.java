package games.brennan.dungeontrain.template;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.slf4j.Logger;

import java.util.List;

/**
 * Resolves which {@link Stage} a carriage slot belongs to, from the same {@code (level, phase)} pair the
 * template gates use. Shared carriages are pooled per stage — a build authored in the stone stretch may
 * only be placed back into a stone stretch — so this is what stamps a build's origin at submit time and
 * what filters the relay lease at spawn time.
 *
 * <p>Stages do <b>not</b> partition {@code (level, phase)} space cleanly. The eight level-banded stages
 * are disjoint (stone 0-10, desert 11-35, … wood_oak 131-200), but {@code nether} carries no level bounds
 * and lists both {@code NETHER} and {@code END}, so a low-level carriage in the End satisfies
 * {@code stone} and {@code nether} at once. "First eligible" would therefore depend on map order; this
 * picks the <b>narrowest level band</b> instead, so the specific stage beats the catch-all, with the id
 * as a final tie-break to keep the answer stable across runs.</p>
 *
 * <p>Pure: takes only a {@link GateContext}, touches no level state, and is safe to call on the spawn
 * path.</p>
 */
public final class StageResolver {

    private static final Logger LOGGER = LogUtils.getLogger();

    private StageResolver() {}

    /**
     * The stage id for {@code ctx}, or {@code null} when no stage covers it. A null means the carriage
     * can never enter the shared pool (the relay refuses to lease a carriage with no stage), so it is
     * logged rather than passed over silently.
     */
    public static String stageIdFor(GateContext ctx) {
        return ctx == null ? null : stageIdFor(ctx.level(), ctx.phase(), games.brennan.dungeontrain.editor.StageStore.allStages());
    }

    /**
     * Testable core: the best-matching stage id among {@code stages} for {@code level}/{@code phase},
     * or null. Package-visible overload so tests can pin behaviour against a fixed stage set instead of
     * whatever the install happens to have loaded.
     */
    public static String stageIdFor(int level, TrainPhase phase, List<Stage> stages) {
        if (stages == null || stages.isEmpty()) return null;
        Stage best = null;
        long bestWidth = Long.MAX_VALUE;
        for (Stage s : stages) {
            TemplateGate g = s.gate();
            if (g == null || !g.eligible(level, phase)) continue;
            long width = bandWidth(g);
            if (best == null || width < bestWidth
                    || (width == bestWidth && s.id().compareTo(best.id()) < 0)) {
                best = s;
                bestWidth = width;
            }
        }
        if (best == null) {
            LOGGER.warn("[DungeonTrain] No stage covers level={} phase={} — carriages there cannot be shared.",
                    level, phase);
            return null;
        }
        return best.id();
    }

    /** Level-band width, with an {@link TemplateGate#ALL} max treated as unbounded (widest possible). */
    private static long bandWidth(TemplateGate gate) {
        if (gate.maxLevel() == TemplateGate.ALL) return Long.MAX_VALUE;
        return (long) gate.maxLevel() - gate.minLevel();
    }
}
