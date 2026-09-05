package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.editor.CarriageContentsGroupStore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What each carriage index was <em>actually</em> built as — its shell variant, its interior
 * contents parent, and the group member that parent resolved to. Recorded at placement time and
 * read back by the F3+4 debug panel.
 *
 * <h2>Why this is recorded rather than recomputed</h2>
 * <p>Re-rolling the pick later cannot work, and not just as an optimisation detail. Both the shell
 * and the contents draw through a {@link games.brennan.dungeontrain.template.GateContext} built
 * from the group's <b>world-X at the moment it was placed</b>
 * ({@code GateContext.forCarriageAtWorldX}), because that is what decides which band — and so which
 * gated candidate pool — the carriage belongs to. The train then moves. The placement-time world-X
 * is gone, and the static {@code pIdx → X} formula that {@code GateContext.forCarriage} falls back
 * on drifts further behind the real placement the further along +X a group sits. A recomputed
 * answer therefore disagrees with the standing carriage, and disagrees by more the longer the run
 * goes — it reads as though the game thinks you are somewhere else on the train entirely.</p>
 *
 * <p>Recording at the one point where the pick actually happens sidesteps all of that, and makes
 * the relay-leased case honest too: a slot stamped verbatim from another player's build never
 * reaches the pick, so it records only what is known about it rather than inventing a roll.</p>
 *
 * <p>Server-thread only, like the placement path that fills it. Bounded to
 * {@value #MAX_ENTRIES} entries — a long run walks through unboundedly many carriage indices, and
 * this is a debug read-out, so the oldest are dropped rather than held forever.</p>
 */
public final class PlacedCarriageFacts {

    /** Roughly a few thousand carriages' worth of ids — far more than any panel will ask about. */
    private static final int MAX_ENTRIES = 4096;

    /** Stand-in contents id for a slot stamped verbatim from the shared-carriage relay pool. */
    public static final String RELAY_BUILD = "(relay build)";

    /**
     * One carriage's identity.
     *
     * @param variantId    the shell variant that was placed
     * @param contentsId   the interior contents parent, or {@link #RELAY_BUILD}
     * @param subVariantId the group member the parent resolved to; empty when the draw landed on
     *                     the parent's own contents, or the parent has no group sidecar
     * @param flip         which axes the interior came out flipped along, as
     *                     {@link ContentsFlip#label} renders them; empty for an index this session
     *                     never placed
     */
    public record Facts(String variantId, String contentsId, String subVariantId, String flip) {
        public Facts {
            variantId = variantId == null ? "" : variantId;
            contentsId = contentsId == null ? "" : contentsId;
            subVariantId = subVariantId == null ? "" : subVariantId;
            flip = flip == null ? "" : flip;
        }

        /** Back-compat form from before the flip was recorded. */
        public Facts(String variantId, String contentsId, String subVariantId) {
            this(variantId, contentsId, subVariantId, "");
        }
    }

    private static final Map<Integer, Facts> BY_PIDX =
        new LinkedHashMap<>(256, 0.75f, /*accessOrder*/ true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Facts> eldest) {
                return size() > MAX_ENTRIES;
            }
        };

    private PlacedCarriageFacts() {}

    /**
     * Record a freshly placed carriage. {@code resolvedContents} is what
     * {@code CarriageContentsRegistry.pick} returned — already group-resolved — so the parent it
     * came through is recovered here rather than at the call site.
     */
    public static synchronized void record(int carriagePIdx, CarriageVariant variant,
                                           CarriageContents resolvedContents,
                                           ContentsFlip.Flip flip) {
        String resolvedId = resolvedContents == null ? "" : resolvedContents.id();
        String parentId = resolvedId.isEmpty()
            ? ""
            : CarriageContentsGroupStore.findParentOf(resolvedId).orElse(resolvedId);
        // Equal ids mean the group draw landed on the parent's own contents, or there is no group
        // at all — either way there is no sub-variant to name.
        String subVariantId = parentId.equals(resolvedId) ? "" : resolvedId;
        BY_PIDX.put(carriagePIdx,
            new Facts(variant == null ? "" : variant.id(), parentId, subVariantId,
                ContentsFlip.label(flip)));
    }

    /**
     * Record a slot stamped verbatim from the shared-carriage relay pool. It never reaches a
     * contents pick, so only the variant it was leased against is known.
     */
    public static synchronized void recordRelayBuild(int carriagePIdx, CarriageVariant variant) {
        // Never flipped: a leased build is stamped verbatim from its relay blob and never reaches
        // the contents placer, so there is no roll to report rather than an unknown one.
        BY_PIDX.put(carriagePIdx,
            new Facts(variant == null ? "" : variant.id(), RELAY_BUILD, "", ContentsFlip.LABEL_NONE));
    }

    /** What was placed at {@code carriagePIdx}, or null if this session never placed it. */
    public static synchronized Facts get(int carriagePIdx) {
        return BY_PIDX.get(carriagePIdx);
    }

    /** Forget everything. Called wherever the trains themselves are torn down. */
    public static synchronized void clear() {
        BY_PIDX.clear();
    }
}
