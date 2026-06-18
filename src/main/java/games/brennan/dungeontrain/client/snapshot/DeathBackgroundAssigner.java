package games.brennan.dungeontrain.client.snapshot;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Assigns one ride photo to each death-screen page, preferring photos not yet
 * used on another page so the pages don't repeat the same shot.
 *
 * <p>Two passes over the page list (run after the survey pages are known):</p>
 * <ol>
 *   <li><b>Preferred</b> — each page with a preferred tag (tier 0 of its chain)
 *       claims the newest unused shot of that tag.</li>
 *   <li><b>Fallback</b> — every still-unassigned page (a preferred miss, plus
 *       the wildcard survey / platform pages) takes the newest <em>unused</em>
 *       shot along its thematic chain, then any unused shot. Only when no unused
 *       shot remains does it <em>reuse</em> one (there are fewer images than
 *       pages); a reused shot prefers one that repeats neither the previous nor
 *       the next page so the same photo never lands on two pages in a row, while
 *       still favouring the thematic chain among the non-repeating candidates.
 *       Repetition happens only when a single distinct image exists.</li>
 * </ol>
 *
 * <p>Each page's chain is a list of tags with tier 0 = preferred; an empty chain
 * is a wildcard (any shot). A {@code null} entry in the result means "no photo"
 * (empty gallery), which the caller paints as the solid overlay. Pure +
 * deterministic for unit testing.</p>
 */
public final class DeathBackgroundAssigner {

    private DeathBackgroundAssigner() {}

    /**
     * @param chains  per-page thematic chains, in page order (tier 0 = preferred; empty = wildcard)
     * @param gallery this run's shots, oldest → newest
     * @return one shot per page (parallel to {@code chains}); entries are {@code null} only for an empty gallery
     */
    public static RideSnapshot[] assign(List<List<SnapshotTag>> chains, List<RideSnapshot> gallery) {
        RideSnapshot[] out = new RideSnapshot[chains.size()];
        if (gallery.isEmpty()) return out;
        Set<RideSnapshot> used = Collections.newSetFromMap(new IdentityHashMap<>());

        // Pass 1: give every page its preferred (tier-0) tag first.
        for (int i = 0; i < chains.size(); i++) {
            List<SnapshotTag> chain = chains.get(i);
            if (chain.isEmpty()) continue;
            RideSnapshot s = newestUnused(gallery, used, chain.get(0));
            if (s != null) {
                out[i] = s;
                used.add(s);
            }
        }
        // Pass 2: fill the rest, preferring unused along the chain, then any unused, then reuse.
        // Filled left-to-right, so out[i-1] is always set; out[i+1] may still be null (a later
        // reuse page) — the reuse step skips null neighbours.
        for (int i = 0; i < chains.size(); i++) {
            if (out[i] != null) continue;
            RideSnapshot prev = i > 0 ? out[i - 1] : null;
            RideSnapshot next = i + 1 < out.length ? out[i + 1] : null;
            RideSnapshot s = fallback(gallery, used, chains.get(i), prev, next);
            if (s != null) {
                out[i] = s;
                used.add(s);
            }
        }
        return out;
    }

    private static RideSnapshot fallback(List<RideSnapshot> gallery, Set<RideSnapshot> used,
                                         List<SnapshotTag> chain, RideSnapshot prev, RideSnapshot next) {
        for (SnapshotTag tag : chain) {
            RideSnapshot s = newestUnused(gallery, used, tag);
            if (s != null) return s;
        }
        RideSnapshot anyUnused = newestUnused(gallery, used, null);
        if (anyUnused != null) return anyUnused;
        return reuse(gallery, chain, prev, next);
    }

    /**
     * Everything is used (fewer images than pages). Reuse the most fitting shot that does not
     * repeat an adjacent page, so the same photo never appears on two pages in a row. Order:
     * chain-fit avoiding both neighbours, any avoiding both, chain-fit avoiding the previous,
     * any avoiding the previous, then (single-image case) the plain best fit. Avoiding the
     * previous page always succeeds while ≥2 distinct images exist, so a repeat only occurs
     * when the gallery holds a single distinct shot.
     */
    private static RideSnapshot reuse(List<RideSnapshot> gallery, List<SnapshotTag> chain,
                                      RideSnapshot prev, RideSnapshot next) {
        for (SnapshotTag tag : chain) {
            RideSnapshot s = newestAvoiding(gallery, tag, prev, next);
            if (s != null) return s;
        }
        RideSnapshot anyBoth = newestAvoiding(gallery, null, prev, next);
        if (anyBoth != null) return anyBoth;
        for (SnapshotTag tag : chain) {
            RideSnapshot s = newestAvoiding(gallery, tag, prev);
            if (s != null) return s;
        }
        RideSnapshot anyPrev = newestAvoiding(gallery, null, prev);
        if (anyPrev != null) return anyPrev;
        // Only one distinct shot exists — repetition is unavoidable.
        for (SnapshotTag tag : chain) {
            RideSnapshot s = newest(gallery, tag);
            if (s != null) return s;
        }
        return newest(gallery, null);
    }

    /** Newest shot matching {@code tag} (any tag if {@code null}) not in {@code used}, else {@code null}. */
    private static RideSnapshot newestUnused(List<RideSnapshot> gallery, Set<RideSnapshot> used, SnapshotTag tag) {
        RideSnapshot match = null;
        for (RideSnapshot s : gallery) {
            if ((tag == null || s.tag() == tag) && !used.contains(s)) match = s;
        }
        return match;
    }

    /** Newest shot matching {@code tag} (any tag if {@code null}), used or not, else {@code null}. */
    private static RideSnapshot newest(List<RideSnapshot> gallery, SnapshotTag tag) {
        RideSnapshot match = null;
        for (RideSnapshot s : gallery) {
            if (tag == null || s.tag() == tag) match = s;
        }
        return match;
    }

    /**
     * Newest shot matching {@code tag} (any tag if {@code null}) whose identity is none of the
     * {@code avoid} shots, else {@code null}. {@code null} avoid entries are ignored, so a page
     * with no neighbour on a side imposes no constraint. Identity ({@code ==}) matches the
     * {@code IdentityHashMap}-based {@code used} set: every capture is a distinct object.
     */
    private static RideSnapshot newestAvoiding(List<RideSnapshot> gallery, SnapshotTag tag, RideSnapshot... avoid) {
        RideSnapshot match = null;
        for (RideSnapshot s : gallery) {
            if (tag != null && s.tag() != tag) continue;
            boolean blocked = false;
            for (RideSnapshot a : avoid) {
                if (a == s) { blocked = true; break; }
            }
            if (!blocked) match = s;
        }
        return match;
    }
}
