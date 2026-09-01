package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import games.brennan.dungeontrain.net.BuilderProfilePacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Which of a player's builds My Builds is currently showing.
 *
 * <p>Pure, and its own class for the reason {@link BuilderTemplateGridLayout} is: the screen around
 * it needs a running client to touch at all, while this is the part that can actually be wrong —
 * a filter that quietly drops a build is indistinguishable from a build that never uploaded.</p>
 *
 * <p>All three axes use the empty string for "everything", which is what the chips start on. It is
 * not a value any axis can otherwise hold — a kind is always one of {@code BuilderRelayKinds}' names,
 * a review state always one of {@link BuilderReviewState}'s, and the favourite axis' only other value
 * is {@link #STARRED} — so no build can be caught by it by accident.</p>
 */
final class BuilderProfileFilters {

    /** Every chip's first option: no narrowing at all. */
    static final String ALL = "";

    /**
     * The favourite chip's other option: only builds this player has starred.
     *
     * <p>One-sided on purpose — there is no "only the ones I haven't starred". A star marks the few
     * things worth coming back to out of many, so narrowing TO them is the whole gesture; narrowing
     * away from them would be asking for the pile you already see.</p>
     */
    static final String STARRED = "starred";

    private BuilderProfileFilters() {}

    /**
     * Whether one build survives both filters.
     *
     * <p>The review state is read through {@link BuilderReviewState#of} rather than compared raw, so
     * a build carrying something this version doesn't know — an older relay's empty string, a state
     * added on the relay first — files under "not submitted" instead of vanishing from every filter
     * including "All", which is the one way a filter can lose a build outright.</p>
     */
    static boolean matches(BuilderProfilePacket.Entry entry, String kind, String review) {
        return matches(entry, kind, review, ALL);
    }

    /**
     * As above, with the favourite axis.
     *
     * <p>Anything other than {@link #STARRED} on that axis is "everything", rather than being compared
     * as a value: the axis has exactly two states and treating an unrecognised one as no narrowing
     * keeps it on the right side of the rule this class exists for — a filter must never be the reason
     * a build disappears from "All".</p>
     */
    static boolean matches(BuilderProfilePacket.Entry entry, String kind, String review, String favourite) {
        if (entry == null) return false;
        if (!ALL.equals(kind) && !kind.equals(entry.kind())) return false;
        if (!ALL.equals(review) && !review.equals(BuilderReviewState.of(entry.review()))) return false;
        return !STARRED.equals(favourite) || entry.favourite();
    }

    /** The builds to draw, in the order the relay listed them. */
    static List<BuilderProfilePacket.Entry> apply(List<BuilderProfilePacket.Entry> builds,
                                                  String kind, String review) {
        return apply(builds, kind, review, ALL);
    }

    /** As above, with the favourite axis. */
    static List<BuilderProfilePacket.Entry> apply(List<BuilderProfilePacket.Entry> builds,
                                                  String kind, String review, String favourite) {
        if (ALL.equals(kind) && ALL.equals(review) && !STARRED.equals(favourite)) return builds;
        List<BuilderProfilePacket.Entry> out = new ArrayList<>(builds.size());
        for (BuilderProfilePacket.Entry entry : builds) {
            if (matches(entry, kind, review, favourite)) out.add(entry);
        }
        return List.copyOf(out);
    }
}
