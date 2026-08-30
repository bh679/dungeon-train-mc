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
 * <p>Both axes use the empty string for "everything", which is what the chips start on. It is not a
 * value either axis can otherwise hold — a kind is always one of {@code BuilderRelayKinds}' names and
 * a review state always one of {@link BuilderReviewState}'s — so no build can be caught by it by
 * accident.</p>
 */
final class BuilderProfileFilters {

    /** Both chips' first option: no narrowing at all. */
    static final String ALL = "";

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
        if (entry == null) return false;
        if (!ALL.equals(kind) && !kind.equals(entry.kind())) return false;
        return ALL.equals(review) || review.equals(BuilderReviewState.of(entry.review()));
    }

    /** The builds to draw, in the order the relay listed them. */
    static List<BuilderProfilePacket.Entry> apply(List<BuilderProfilePacket.Entry> builds,
                                                  String kind, String review) {
        if (ALL.equals(kind) && ALL.equals(review)) return builds;
        List<BuilderProfilePacket.Entry> out = new ArrayList<>(builds.size());
        for (BuilderProfilePacket.Entry entry : builds) {
            if (matches(entry, kind, review)) out.add(entry);
        }
        return List.copyOf(out);
    }
}
