package games.brennan.dungeontrain.player;

/**
 * Pure slot geometry for the Free Play Ender Chest, plain and expanded. No Minecraft imports —
 * unit-tested.
 *
 * <p>Plain is vanilla's 9×3. Expanded is {@code (5 + 9 + 5) × (3 + 3)}: the vanilla block stays at the
 * bottom-centre of a 19-wide, 6-tall grid, three more rows sit above it inside the window, and a
 * five-column <em>wing</em> flanks each side for all six rows — the two panels Edible Backpacks hangs
 * beside the inventory screen, on a chest.</p>
 *
 * <p><b>Index contract.</b> Slots 0–26 are always the vanilla 27, in vanilla's order, whether or not the
 * chest is expanded — they are what EnderChestPersistence has on disk for every existing player and
 * what a plain 27-slot container maps back onto. The extra 87 come after: the three upper centre rows
 * (27–53), then the left wing (54–83), then the right wing (84–113), each wing filling down a column and
 * then outward, like Edible Backpacks'. So a shrink drops exactly the slots past 26 and nothing moves.</p>
 *
 * <p>Coordinates are relative to the screen's {@code leftPos}/{@code topPos}, like every vanilla slot;
 * the window itself is vanilla's 176-wide chest GUI, so the wings sit outside it at negative x / past
 * 176, exactly as the backpack panels do.</p>
 */
public final class EnderChestLayout {

    public static final int SLOT = 18;
    public static final int GUI_WIDTH = 176;

    public static final int CENTRE_COLUMNS = 9;
    public static final int VANILLA_ROWS = 3;
    public static final int VANILLA_SLOTS = CENTRE_COLUMNS * VANILLA_ROWS;   // 27

    public static final int EXTRA_ROWS = 3;
    public static final int WING_COLUMNS = 5;
    public static final int EXPANDED_ROWS = VANILLA_ROWS + EXTRA_ROWS;      // 6
    public static final int EXPANDED_COLUMNS = CENTRE_COLUMNS + 2 * WING_COLUMNS; // 19
    public static final int EXPANDED_SLOTS = EXPANDED_ROWS * EXPANDED_COLUMNS;  // 114

    public static final int UPPER_CENTRE_SLOTS = CENTRE_COLUMNS * EXTRA_ROWS;   // 27
    public static final int WING_SLOTS = WING_COLUMNS * EXPANDED_ROWS;          // 30

    /** First index of each region. */
    public static final int UPPER_CENTRE_START = VANILLA_SLOTS;                 // 27
    public static final int LEFT_WING_START = UPPER_CENTRE_START + UPPER_CENTRE_SLOTS; // 54
    public static final int RIGHT_WING_START = LEFT_WING_START + WING_SLOTS;    // 84

    /** Vanilla chest slot origin inside the window. */
    public static final int CENTRE_X0 = 8;
    public static final int ROW_Y0 = 18;

    /** Gap between a wing's inner edge and the window edge — same as Edible Backpacks. */
    public static final int GAP = 8;
    /** Chrome drawn around a wing's slots, on every edge. */
    public static final int BORDER = 4;
    /** Left wing's innermost column x; the wing grows leftward from here. */
    public static final int LEFT_INNER_X = -(GAP + SLOT);       // -26
    /** Right wing's innermost column x; the wing grows rightward from here. */
    public static final int RIGHT_INNER_X = GUI_WIDTH + GAP;    // 184

    private EnderChestLayout() {}

    /** Which block of the grid an index belongs to. */
    public enum Region { VANILLA, UPPER_CENTRE, LEFT_WING, RIGHT_WING }

    public static int slots(boolean expanded) {
        return expanded ? EXPANDED_SLOTS : VANILLA_SLOTS;
    }

    public static int rows(boolean expanded) {
        return expanded ? EXPANDED_ROWS : VANILLA_ROWS;
    }

    public static Region regionOf(int index) {
        if (index < 0 || index >= EXPANDED_SLOTS) {
            throw new IllegalArgumentException("slot index out of range: " + index);
        }
        if (index < UPPER_CENTRE_START) return Region.VANILLA;
        if (index < LEFT_WING_START) return Region.UPPER_CENTRE;
        if (index < RIGHT_WING_START) return Region.LEFT_WING;
        return Region.RIGHT_WING;
    }

    /** True for a slot that only exists once expanded — the ones a shrink would drop. */
    public static boolean isExtra(int index) {
        return index >= VANILLA_SLOTS;
    }

    /** Wing column, 0 = innermost (nearest the window), growing outward. */
    public static int wingColumn(int index) {
        return ((index - wingStart(index)) / EXPANDED_ROWS);
    }

    private static int wingStart(int index) {
        return regionOf(index) == Region.LEFT_WING ? LEFT_WING_START : RIGHT_WING_START;
    }

    /** Slot x relative to {@code leftPos}. */
    public static int slotX(int index, boolean expanded) {
        if (!expanded && isExtra(index)) {
            throw new IllegalArgumentException("slot " + index + " does not exist in the plain chest");
        }
        return switch (regionOf(index)) {
            case VANILLA -> CENTRE_X0 + (index % CENTRE_COLUMNS) * SLOT;
            case UPPER_CENTRE -> CENTRE_X0 + ((index - UPPER_CENTRE_START) % CENTRE_COLUMNS) * SLOT;
            case LEFT_WING -> LEFT_INNER_X - wingColumn(index) * SLOT;
            case RIGHT_WING -> RIGHT_INNER_X + wingColumn(index) * SLOT;
        };
    }

    /** Slot y relative to {@code topPos}. The vanilla block drops three rows when expanded. */
    public static int slotY(int index, boolean expanded) {
        if (!expanded && isExtra(index)) {
            throw new IllegalArgumentException("slot " + index + " does not exist in the plain chest");
        }
        int row = switch (regionOf(index)) {
            case VANILLA -> (expanded ? EXTRA_ROWS : 0) + index / CENTRE_COLUMNS;
            case UPPER_CENTRE -> (index - UPPER_CENTRE_START) / CENTRE_COLUMNS;
            case LEFT_WING, RIGHT_WING -> (index - wingStart(index)) % EXPANDED_ROWS;
        };
        return ROW_Y0 + row * SLOT;
    }

    /**
     * A wing's chrome rectangle relative to {@code leftPos}/{@code topPos}: {x0, y0, x1, y1}, exclusive
     * on the far side. Spans all six rows plus {@link #BORDER} on every edge.
     */
    public static int[] wingBounds(boolean right) {
        int inner = right ? RIGHT_INNER_X : LEFT_INNER_X;
        int outer = right ? inner + (WING_COLUMNS - 1) * SLOT : inner - (WING_COLUMNS - 1) * SLOT;
        int x0 = Math.min(inner, outer) - BORDER;
        int x1 = Math.max(inner, outer) + SLOT + BORDER;
        int y0 = ROW_Y0 - BORDER;
        int y1 = ROW_Y0 + EXPANDED_ROWS * SLOT + BORDER;
        return new int[] {x0, y0, x1, y1};
    }
}
