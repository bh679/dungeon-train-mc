package games.brennan.dungeontrain.client.menu.plot;

import games.brennan.dungeontrain.net.EditorTypeMenusPacket;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;

/**
 * The Stages panel's column sort — view state only, held on the client like remove-mode.
 *
 * <p>The panel's rows arrive from the server in one order and every click on a row is sent back
 * as that row's <b>server index</b>, so sorting never reorders the list itself: {@link #order}
 * answers "which server index sits at display row {@code r}", and the renderer draws and hit-tests
 * through that permutation. Both sides call the same pure function with the same inputs, which is
 * what keeps a click on a sorted row landing on the stage that was drawn there.</p>
 *
 * <p>A title click sorts ascending; clicking the same title again flips it to descending. Ties keep
 * the server's order (the sort is stable), so a column of equal values reads exactly as before.</p>
 */
public final class StagesSort {

    /** The sortable columns, in the order the titles row draws them. */
    public enum Column {
        NAME("Name"), BLOCKS("Blocks"), MIN("Min"), MAX("Max"), PHASES("Phases");

        private final String title;

        Column(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    private static volatile Column column = null;
    private static volatile boolean descending = false;

    private StagesSort() {}

    /** The sorted column, or {@code null} for the server's order. */
    public static Column column() {
        return column;
    }

    public static boolean descending() {
        return descending;
    }

    /** A title click: a new column sorts ascending; the same column again flips the direction. */
    public static void click(Column clicked) {
        if (clicked == null) return;
        if (clicked == column) {
            descending = !descending;
        } else {
            column = clicked;
            descending = false;
        }
    }

    /** Back to the server's order — on leaving the editor, with the rest of the panel state. */
    public static void clear() {
        column = null;
        descending = false;
    }

    /** {@link #order} with the live sort state. */
    public static int[] order(List<EditorTypeMenusPacket.Variant> variants, ToIntFunction<String> blockCount) {
        return order(variants, blockCount, column, descending);
    }

    /**
     * Display row → server index for {@code variants} under the given sort. Identity when
     * {@code column} is null. Pure (params in), so the table is unit-tested without a client.
     *
     * @param blockCount how many distinct blocks a stage (by model id) has in its icon strip —
     *                   the BLOCKS column's key
     */
    public static int[] order(List<EditorTypeMenusPacket.Variant> variants, ToIntFunction<String> blockCount,
                              Column column, boolean descending) {
        int n = variants.size();
        if (column == null) return IntStream.range(0, n).toArray();
        Comparator<Integer> byKey = keyComparator(variants, blockCount, column);
        if (descending) byKey = byKey.reversed();
        // Stable: equal keys keep their server order in either direction.
        return IntStream.range(0, n).boxed().sorted(byKey).mapToInt(Integer::intValue).toArray();
    }

    private static Comparator<Integer> keyComparator(List<EditorTypeMenusPacket.Variant> variants,
                                                     ToIntFunction<String> blockCount, Column column) {
        return switch (column) {
            case NAME -> Comparator.comparing((Integer i) -> variants.get(i).displayName().toLowerCase(Locale.ROOT))
                .thenComparing(i -> variants.get(i).modelId());
            case BLOCKS -> Comparator.comparingInt(i -> blockCount.applyAsInt(variants.get(i).modelId()));
            case MIN -> Comparator.comparingInt(i -> variants.get(i).minLevel());
            // An open-ended max (-1 on the wire, drawn as ∞) is the largest value, not the smallest.
            case MAX -> Comparator.comparingLong(i -> maxKey(variants.get(i).maxLevel()));
            case PHASES -> Comparator.comparingInt(i -> Integer.bitCount(variants.get(i).phaseMask()));
        };
    }

    static long maxKey(int maxLevel) {
        return maxLevel < 0 ? Long.MAX_VALUE : maxLevel;
    }
}
