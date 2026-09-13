package games.brennan.dungeontrain.client.menu.stagepalette;

import games.brennan.dungeontrain.block.stage.StageStoneFamily.StoneKind;
import games.brennan.dungeontrain.net.StagePaletteSyncPacket;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client-side singleton state for the Stage Palette panel — the billboard beside the Stage Blocks
 * panel listing every stage placeholder block and the block it resolves to for the selected stage.
 * Fed by {@link StagePaletteSyncPacket}; ops go back via
 * {@link games.brennan.dungeontrain.net.StagePaletteEditPacket}. Same state-singleton shape as
 * {@link games.brennan.dungeontrain.client.menu.stagepanel.StagePanelMenu}.
 *
 * <p>Clicking a cell overrides that placeholder with the player's <b>held block</b> (empty hand
 * clears the override); clicking the Wood / Stone header picks the family the held block belongs
 * to. The row layout is fixed ({@link #LAYOUT}) and shared with the renderer's hit test.</p>
 */
public final class StagePaletteMenu {

    public enum CellKind {
        NONE,
        /** Toolbar: re-derive the palette (overrides kept). */
        REBAKE,
        /** Toolbar: closes the panel (deselects the stage). */
        CLOSE,
        /** A placeholder cell — {@code index} = layout row, {@code secondary} = {@link Column} ordinal. */
        CELL,
        /** The "Wood: <family>" header — click with a held wood block to set the family. */
        WOOD_HEADER,
        /** The "Stone: <family>" header — likewise for stone. */
        STONE_HEADER
    }

    public record Hit(CellKind kind, int index, int secondary) {
        public static final Hit NONE = new Hit(CellKind.NONE, -1, -1);
    }

    /** Table columns — the block <em>types</em>; every cell row maps a subset of these to a placeholder. */
    public enum Column {
        BLOCK("Block"), STAIRS("Stairs"), SLAB("Slab"), WALL("Wall"), BUTTON("Button"), PLATE("Plate"),
        FENCE("Fence"), GATE("Gate"), DOOR("Door"), TRAPDOOR("Trapdoor"), LOG("Log"),
        STRIPPED_LOG("Str. log"), WOOD("Wood"), STRIPPED_WOOD("Str. wood");

        private final String label;

        Column(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum RowKind { HEADER, TOOLBAR, COLUMNS, CELLS, STATUS }

    /**
     * One table row. {@code cells} maps a column to a placeholder name; {@code labelAction} is the
     * hit kind of the row-label column ({@link CellKind#NONE} for plain labels, WOOD_HEADER /
     * STONE_HEADER for the family rows).
     */
    public record Row(RowKind kind, String label, Map<Column, String> cells, CellKind labelAction) {
        static Row plain(RowKind kind) { return new Row(kind, "", Map.of(), CellKind.NONE); }
        static Row cells(String label, Map<Column, String> cells) { return new Row(RowKind.CELLS, label, cells, CellKind.NONE); }
        static Row family(String label, CellKind action, Map<Column, String> cells) { return new Row(RowKind.CELLS, label, cells, action); }
        public String cell(Column c) { return cells.get(c); }
    }

    /** The panel's rows, top to bottom. */
    public static final List<Row> LAYOUT = buildLayout();

    private static boolean active = false;
    private static String stageId = "";
    private static BlockPos anchor = BlockPos.ZERO;
    private static Map<String, StagePaletteSyncPacket.Entry> entries = Map.of();
    private static String wood = "";
    private static String stone = "";
    private static boolean woodLocked = false;
    private static boolean stoneLocked = false;
    private static volatile Hit hovered = Hit.NONE;

    private StagePaletteMenu() {}

    private static List<Row> buildLayout() {
        List<Row> rows = new ArrayList<>();
        rows.add(Row.plain(RowKind.HEADER));
        rows.add(Row.plain(RowKind.TOOLBAR));
        rows.add(Row.plain(RowKind.COLUMNS));
        for (int i = 1; i <= 10; i++) {
            Map<Column, String> cells = new EnumMap<>(Column.class);
            cells.put(Column.BLOCK, "stage_block_" + i);
            if (i <= 2) {
                cells.put(Column.STAIRS, "stage_stairs_" + i);
                cells.put(Column.SLAB, "stage_slab_" + i);
            }
            if (i == 1) {
                cells.put(Column.BUTTON, "stage_button");
                cells.put(Column.PLATE, "stage_pressure_plate");
            }
            rows.add(Row.cells("Solid " + i, cells));
        }
        Map<Column, String> wood = new EnumMap<>(Column.class);
        wood.put(Column.BLOCK, "stage_planks");
        wood.put(Column.STAIRS, "stage_wood_stairs");
        wood.put(Column.SLAB, "stage_wood_slab");
        wood.put(Column.BUTTON, "stage_wood_button");
        wood.put(Column.PLATE, "stage_wood_pressure_plate");
        wood.put(Column.FENCE, "stage_fence");
        wood.put(Column.GATE, "stage_fence_gate");
        wood.put(Column.DOOR, "stage_door");
        wood.put(Column.TRAPDOOR, "stage_trapdoor");
        wood.put(Column.LOG, "stage_log");
        wood.put(Column.STRIPPED_LOG, "stage_stripped_log");
        wood.put(Column.WOOD, "stage_wood");
        wood.put(Column.STRIPPED_WOOD, "stage_stripped_wood");
        rows.add(Row.family("Wood", CellKind.WOOD_HEADER, wood));
        rows.add(Row.family("Stone", CellKind.STONE_HEADER, Map.of()));
        for (StoneKind kind : StoneKind.values()) {
            String base = kind == StoneKind.STONE ? "stage_stone" : "stage_stone_" + kind.id();
            Map<Column, String> cells = new EnumMap<>(Column.class);
            cells.put(Column.BLOCK, base);
            cells.put(Column.STAIRS, base + "_stairs");
            cells.put(Column.SLAB, base + "_slab");
            cells.put(Column.WALL, base + "_wall");
            rows.add(Row.cells("  " + kind.id(), cells));
        }
        rows.add(Row.plain(RowKind.STATUS));
        return List.copyOf(rows);
    }

    /** Apply a server snapshot. {@code open == false} closes. */
    public static synchronized void applySync(StagePaletteSyncPacket packet) {
        if (!packet.open()) {
            closeLocal();
            return;
        }
        active = true;
        stageId = packet.stageId().toLowerCase(Locale.ROOT);
        anchor = packet.anchorPos();
        Map<String, StagePaletteSyncPacket.Entry> map = new HashMap<>();
        for (StagePaletteSyncPacket.Entry e : packet.entries()) map.put(e.name(), e);
        entries = Map.copyOf(map);
        wood = packet.wood();
        stone = packet.stone();
        woodLocked = packet.woodLocked();
        stoneLocked = packet.stoneLocked();
        hovered = Hit.NONE;
    }

    /** Client-side reset — editor exit, logout, or a {@code closed()} sync. */
    public static synchronized void closeLocal() {
        active = false;
        stageId = "";
        anchor = BlockPos.ZERO;
        entries = Map.of();
        wood = "";
        stone = "";
        woodLocked = false;
        stoneLocked = false;
        hovered = Hit.NONE;
    }

    // ---------- accessors ----------

    public static boolean isActive() { return active; }
    public static String stageId() { return stageId; }
    public static BlockPos anchor() { return anchor; }
    /** The synced entry for a placeholder name, or null before the first sync. */
    public static StagePaletteSyncPacket.Entry entry(String name) { return entries.get(name); }
    public static String wood() { return wood; }
    public static String stone() { return stone; }
    public static boolean woodLocked() { return woodLocked; }
    public static boolean stoneLocked() { return stoneLocked; }
    public static Hit hovered() { return hovered; }
    public static void setHovered(Hit hit) { hovered = hit == null ? Hit.NONE : hit; }

    /** The placeholder name a {@link CellKind#CELL} hit points at ({@code secondary} = column ordinal), or null. */
    public static String cellName(Hit hit) {
        if (hit == null || hit.kind() != CellKind.CELL) return null;
        if (hit.index() < 0 || hit.index() >= LAYOUT.size()) return null;
        Column[] cols = Column.values();
        if (hit.secondary() < 0 || hit.secondary() >= cols.length) return null;
        return LAYOUT.get(hit.index()).cell(cols[hit.secondary()]);
    }
}
