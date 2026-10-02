package games.brennan.dungeontrain.block.stage;

import games.brennan.dungeontrain.block.stage.StageStoneFamily.StoneKind;
import games.brennan.dungeontrain.template.StagePalette;

import java.util.ArrayList;
import java.util.List;

/**
 * How the Stage Blocks creative tab is laid out, each group starting at the left edge of a row:
 * the numbered blocks and shapes as one run, the wood solids with their stairs and slab, the wood
 * fittings, the stone kinds two to a row (block, stairs, slab, wall — a blank slot between the
 * pair, so the shapes line up as columns), terracotta beside concrete, then glass.
 *
 * <p>The tab's contents carry the placeholders in this order; the blank slots cannot live there (a
 * tab rejects empty stacks), so the creative screen pads its own item list —
 * {@code mixin.client.CreativeStageBlocksLayoutMixin}.</p>
 */
public final class StageBlocksTabLayout {

    /** Slots per row of the creative item grid. */
    public static final int COLUMNS = 9;

    /** A deliberately blank slot inside a row. */
    public static final String GAP = "";

    private static final List<List<String>> ROWS = buildRows();

    private StageBlocksTabLayout() {}

    /**
     * The grid row by row, top to bottom: placeholder names (no namespace) and {@link #GAP}s. No row
     * exceeds {@link #COLUMNS}; a short row is blank to its end.
     */
    public static List<List<String>> rows() {
        return ROWS;
    }

    /** Every placeholder name in grid order — the order the tab lists its items in. */
    public static List<String> names() {
        List<String> out = new ArrayList<>();
        for (List<String> row : ROWS) {
            for (String cell : row) {
                if (!cell.equals(GAP)) out.add(cell);
            }
        }
        return out;
    }

    /**
     * {@code items} — one per placeholder, in {@link #names()} order — spread over the grid with
     * {@code blank} in every gap and at the end of each short row. Returned unchanged when the count
     * does not match the layout, so a filtered list is never mis-aligned.
     */
    public static <T> List<T> padded(List<T> items, T blank) {
        if (items.size() != names().size()) return items;
        List<T> out = new ArrayList<>(ROWS.size() * COLUMNS);
        int next = 0;
        for (List<String> row : ROWS) {
            for (int i = 0; i < COLUMNS; i++) {
                boolean filled = i < row.size() && !row.get(i).equals(GAP);
                out.add(filled ? items.get(next++) : blank);
            }
        }
        return out;
    }

    private static List<List<String>> buildRows() {
        List<List<String>> rows = new ArrayList<>();

        List<String> numbered = new ArrayList<>();
        for (int i = 1; i <= StagePalette.SOLID_SLOTS; i++) numbered.add("stage_block_" + i);
        for (int i = 1; i <= StagePalette.STAIRS_SLOTS; i++) numbered.add("stage_stairs_" + i);
        for (int i = 1; i <= StagePalette.SLAB_SLOTS; i++) numbered.add("stage_slab_" + i);
        numbered.add("stage_button");
        numbered.add("stage_pressure_plate");
        for (int from = 0; from < numbered.size(); from += COLUMNS) {
            rows.add(numbered.subList(from, Math.min(from + COLUMNS, numbered.size())));
        }

        rows.add(List.of("stage_log", "stage_stripped_log", "stage_wood", "stage_stripped_wood",
            "stage_planks", "stage_leaves", "stage_wood_stairs", "stage_wood_slab"));
        rows.add(List.of("stage_fence", "stage_fence_gate", "stage_wood_button",
            "stage_wood_pressure_plate", "stage_door", "stage_trapdoor"));

        List<List<String>> stones = new ArrayList<>();
        for (StoneKind kind : StoneKind.values()) {
            String base = StagePlaceholderBlocks.stoneName(kind);
            stones.add(List.of(base, base + "_stairs", base + "_slab", base + "_wall"));
        }
        for (int i = 0; i < stones.size(); i += 2) {
            rows.add(i + 1 < stones.size() ? pair(stones.get(i), stones.get(i + 1)) : stones.get(i));
        }

        List<String> terracotta = new ArrayList<>();
        List<String> concrete = new ArrayList<>();
        for (String role : StagePlaceholderBlocks.COLOUR_ROLES) {
            terracotta.add("stage_terracotta_" + role);
            concrete.add("stage_concrete_" + role);
        }
        terracotta.add("stage_glazed_terracotta");
        rows.add(pair(terracotta, concrete));

        List<String> glass = new ArrayList<>();
        for (String role : StagePlaceholderBlocks.GLASS_ROLES) glass.add("stage_glass_" + role);
        for (String role : StagePlaceholderBlocks.GLASS_ROLES) glass.add("stage_glass_pane_" + role);
        rows.add(glass);

        List<List<String>> out = new ArrayList<>();
        for (List<String> row : rows) out.add(List.copyOf(row));
        return List.copyOf(out);
    }

    /** Two groups on one row with a {@link #GAP} between them. */
    private static List<String> pair(List<String> left, List<String> right) {
        List<String> row = new ArrayList<>(left);
        row.add(GAP);
        row.addAll(right);
        return row;
    }
}
