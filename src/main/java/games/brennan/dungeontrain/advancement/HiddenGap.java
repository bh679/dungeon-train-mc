package games.brennan.dungeontrain.advancement;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The "…" between two advancements on the screen when hidden ones sit between them.
 *
 * <p>On the frontier tabs the server hides unearned interior advancements and re-points an earned one
 * at its nearest visible ancestor ({@code PlayerAdvancementsRehomeMixin}), so the screen would draw the
 * two as directly linked. When {@link #MIN_HIDDEN_FOR_MARKER} or more were skipped, the client gives the
 * link an empty column and draws three dots across it, so the player can tell something is missing there.</p>
 *
 * <p>Pure over generic node types so each step can be tested without a game: the server-side walk
 * ({@link #walk}), the client-side column shift ({@link #shiftColumns}) and the line split for the dots
 * ({@link #split}).</p>
 */
public final class HiddenGap {

    /** Fewest hidden advancements between two visible ones that earns a "…". One hidden draws a plain line. */
    public static final int MIN_HIDDEN_FOR_MARKER = 2;

    /** Pixels between dot centres. */
    public static final int DOT_SPACING = 3;

    /** Pixels of line left out either side of the middle dot. */
    public static final int GAP_HALF_WIDTH = 5;

    /**
     * How far a connector's child-side end sits from the child tile's left edge: the line ends at the
     * tile's centre (13px in) and the frame starts 3px in.
     */
    public static final int CHILD_CENTRE_TO_FRAME = 10;

    private HiddenGap() {}

    /** The nearest visible ancestor of a node, and how many hidden ones were passed on the way. */
    public record Walk<N>(N ancestor, int skipped) {}

    public static boolean isMarked(int skipped) {
        return skipped >= MIN_HIDDEN_FOR_MARKER;
    }

    /**
     * Walks up from {@code node}'s parent to the first visible ancestor.
     *
     * @return that ancestor ({@code null} when none is visible) and the number of hidden nodes passed;
     *         {@code skipped} is 0 when the direct parent is visible or there is none
     */
    public static <N> Walk<N> walk(N node, Function<N, N> parentOf, Predicate<N> isVisible) {
        int skipped = 0;
        N ancestor = parentOf.apply(node);
        while (ancestor != null && !isVisible.test(ancestor)) {
            skipped++;
            ancestor = parentOf.apply(ancestor);
        }
        return new Walk<>(ancestor, ancestor == null ? 0 : skipped);
    }

    /**
     * Moves every node right by the number of marked links on its path from the root, its own included,
     * so each marked link spans an extra empty column for the dots. Run on a laid-out tree before any
     * column scaling. The tidy-tree layout gives every subtree its own rows, so moving a subtree right
     * never runs it into a sibling's.
     *
     * @param getX   a node's column
     * @param setX   sets a node's column
     */
    public static <N> void shiftColumns(N root, Function<N, Iterable<N>> childrenOf, Predicate<N> marked,
                                        Function<N, Float> getX, BiConsumer<N, Float> setX) {
        shift(root, 0, childrenOf, marked, getX, setX);
    }

    private static <N> void shift(N node, int inherited, Function<N, Iterable<N>> childrenOf, Predicate<N> marked,
                                  Function<N, Float> getX, BiConsumer<N, Float> setX) {
        int shift = inherited + (marked.test(node) ? 1 : 0);
        if (shift != 0) setX.accept(node, getX.apply(node) + shift);
        for (N child : childrenOf.apply(node)) {
            shift(child, shift, childrenOf, marked, getX, setX);
        }
    }

    /** A connector with a gap cut out of it: the line pieces to draw and the x of each dot. */
    public record Split(List<int[]> segments, int[] dots) {
        public boolean hasDots() {
            return dots.length > 0;
        }
    }

    /**
     * Cuts the dots' gap out of a horizontal connector running from the elbow to a child tile's centre.
     * The dots sit in the middle of the open stretch between the elbow and the child's frame; when that
     * stretch is too short to hold them the line comes back whole and no dots are drawn.
     *
     * @param from        one end of the line, as passed to {@code hLine}
     * @param to          the other end
     * @param childCentre x of the child tile's centre (the line's child-side end)
     */
    public static Split split(int from, int to, int childCentre) {
        int lo = Math.min(from, to);
        int hi = Math.max(from, to);
        int open = Math.min(hi, childCentre - CHILD_CENTRE_TO_FRAME);
        int mid = (lo + open) / 2;
        if (mid - GAP_HALF_WIDTH <= lo || mid + GAP_HALF_WIDTH >= open) {
            return new Split(List.of(new int[] {from, to}), new int[0]);
        }
        List<int[]> segments = new ArrayList<>(2);
        segments.add(new int[] {lo, mid - GAP_HALF_WIDTH});
        segments.add(new int[] {mid + GAP_HALF_WIDTH, hi});
        return new Split(List.copyOf(segments), new int[] {mid - DOT_SPACING, mid, mid + DOT_SPACING});
    }
}
