package games.brennan.dungeontrain.client.menu.stagepalette;

import games.brennan.dungeontrain.block.stage.StageStoneFamily.StoneKind;
import games.brennan.dungeontrain.net.StagePaletteSyncPacket;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
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
        /** A placeholder cell — {@code index} = layout row, {@code secondary} = cell in that row. */
        CELL,
        /** The "Wood: <family>" header — click with a held wood block to set the family. */
        WOOD_HEADER,
        /** The "Stone: <family>" header — likewise for stone. */
        STONE_HEADER
    }

    public record Hit(CellKind kind, int index, int secondary) {
        public static final Hit NONE = new Hit(CellKind.NONE, -1, -1);
    }

    /** One layout row. {@code cells} are placeholder names; {@code label} is the left/centre text. */
    public record Row(RowKind kind, String label, List<String> cells) {
        static Row header() { return new Row(RowKind.HEADER, "", List.of()); }
        static Row toolbar() { return new Row(RowKind.TOOLBAR, "", List.of()); }
        static Row sub(String label) { return new Row(RowKind.SUBHEADER, label, List.of()); }
        static Row wood() { return new Row(RowKind.WOOD_HEADER, "Wood", List.of()); }
        static Row stone() { return new Row(RowKind.STONE_HEADER, "Stone", List.of()); }
        static Row cells(String label, String... names) { return new Row(RowKind.CELLS, label, List.of(names)); }
        static Row status() { return new Row(RowKind.STATUS, "", List.of()); }
    }

    public enum RowKind { HEADER, TOOLBAR, SUBHEADER, WOOD_HEADER, STONE_HEADER, CELLS, STATUS }

    /** The panel's rows, top to bottom. Cell rows carry placeholder registry names. */
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
        rows.add(Row.header());
        rows.add(Row.toolbar());
        rows.add(Row.sub("Solid (most used)"));
        rows.add(Row.cells("", "stage_block_1", "stage_block_2", "stage_block_3", "stage_block_4", "stage_block_5"));
        rows.add(Row.cells("", "stage_block_6", "stage_block_7", "stage_block_8", "stage_block_9", "stage_block_10"));
        rows.add(Row.sub("Fittings"));
        rows.add(Row.cells("", "stage_stairs_1", "stage_stairs_2", "stage_slab_1", "stage_slab_2",
            "stage_button", "stage_pressure_plate"));
        rows.add(Row.wood());
        rows.add(Row.cells("", "stage_log", "stage_stripped_log", "stage_wood", "stage_stripped_wood"));
        rows.add(Row.cells("", "stage_planks", "stage_wood_stairs", "stage_wood_slab", "stage_fence", "stage_fence_gate"));
        rows.add(Row.cells("", "stage_wood_button", "stage_wood_pressure_plate", "stage_door", "stage_trapdoor"));
        rows.add(Row.stone());
        for (StoneKind kind : StoneKind.values()) {
            String base = kind == StoneKind.STONE ? "stage_stone" : "stage_stone_" + kind.id();
            rows.add(Row.cells(kind.id(), base, base + "_stairs", base + "_slab", base + "_wall"));
        }
        rows.add(Row.status());
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

    /** The placeholder name a {@link CellKind#CELL} hit points at, or null. */
    public static String cellName(Hit hit) {
        if (hit == null || hit.kind() != CellKind.CELL) return null;
        if (hit.index() < 0 || hit.index() >= LAYOUT.size()) return null;
        List<String> cells = LAYOUT.get(hit.index()).cells();
        return hit.secondary() >= 0 && hit.secondary() < cells.size() ? cells.get(hit.secondary()) : null;
    }
}
