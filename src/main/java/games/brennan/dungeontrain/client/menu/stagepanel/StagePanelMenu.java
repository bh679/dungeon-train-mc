package games.brennan.dungeontrain.client.menu.stagepanel;

import games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenu;
import games.brennan.dungeontrain.net.StageBlocksSyncPacket;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Client-side singleton state for the Stage Blocks panel (the "stage V menu") — the sibling
 * billboard beside the Stages panel showing the selected stage's aggregated block grid, its parts
 * list (each with its own icon strip), and the Duplicate / Hide-unused / Close toolbar. Fed by
 * {@link StageBlocksSyncPacket}; ops go back via
 * {@link games.brennan.dungeontrain.net.StagePanelEditPacket}. Mirrors
 * {@link BlockVariantMenu}'s state-singleton shape.
 *
 * <p>Sub-screens: {@link Screen#ROOT} (grid + parts + toolbar), {@link Screen#REPLACE_SEARCH}
 * (pick the replacement block for {@link #replaceFrom}), {@link Screen#CONFIRM_REPLACE} (red
 * confirm band before the irreversible stage-wide rewrite).</p>
 */
public final class StagePanelMenu {

    public enum Screen { ROOT, REPLACE_SEARCH, CONFIRM_REPLACE }

    /** What a raycast hit resolves to. {@code index}/{@code secondary} meaning depends on kind. */
    public enum CellKind {
        NONE,
        /** Toolbar: opens the duplicate-name typing screen. */
        DUPLICATE,
        /** Toolbar: toggles the hide-unused-parts filter (server round-trip). */
        HIDE_TOGGLE,
        /** Toolbar: closes the panel. */
        CLOSE,
        /** Aggregated grid cell — {@code index} = index into {@link #blocks()}. */
        BLOCK_CELL,
        /** Part-row icon — {@code index} = part row, {@code secondary} = icon index in its strip. */
        PART_BLOCK,
        /** Search screen: the "&lt; Back" chip. */
        SEARCH_BACK,
        /** Search screen: the typing field (opens the capture screen). */
        SEARCH_FIELD,
        /** Search screen: a result row — {@code index} into {@link #filteredBlockIds()}. */
        SEARCH_RESULT,
        /** Confirm screen buttons. */
        CONFIRM_YES,
        CONFIRM_NO
    }

    public record Hit(CellKind kind, int index, int secondary) {
        public static final Hit NONE = new Hit(CellKind.NONE, -1, -1);
    }

    private static boolean active = false;
    private static String stageId = "";
    private static String stageName = "";
    private static BlockPos anchor = BlockPos.ZERO;
    private static List<String> blocks = List.of();
    private static int totalBlocks = 0;
    private static List<StageBlocksSyncPacket.PartEntry> parts = List.of();
    private static boolean hideUnused = false;
    private static Screen screen = Screen.ROOT;
    private static String searchBuffer = "";
    private static String replaceFrom = "";
    private static String replaceTo = "";
    private static volatile Hit hovered = Hit.NONE;

    private StagePanelMenu() {}

    /** Apply a server snapshot. {@code open == false} closes; a stage change resets sub-screens. */
    public static synchronized void applySync(StageBlocksSyncPacket packet) {
        if (!packet.open()) {
            closeLocal();
            return;
        }
        boolean stageChanged = !packet.stageId().equalsIgnoreCase(stageId);
        active = true;
        stageId = packet.stageId().toLowerCase(Locale.ROOT);
        stageName = packet.stageName();
        anchor = packet.anchorPos();
        blocks = packet.blocks();
        totalBlocks = packet.totalBlocks();
        parts = packet.parts();
        hideUnused = packet.hideUnused();
        if (stageChanged) {
            screen = Screen.ROOT;
            searchBuffer = "";
            replaceFrom = "";
            replaceTo = "";
        }
        hovered = Hit.NONE;
    }

    /** Client-side reset — editor exit, logout, or a {@code closed()} sync. */
    public static synchronized void closeLocal() {
        active = false;
        stageId = "";
        stageName = "";
        anchor = BlockPos.ZERO;
        blocks = List.of();
        totalBlocks = 0;
        parts = List.of();
        hideUnused = false;
        screen = Screen.ROOT;
        searchBuffer = "";
        replaceFrom = "";
        replaceTo = "";
        hovered = Hit.NONE;
    }

    /** Enter the replacement search for {@code fromBlockId} (clicked in the grid or a part strip). */
    public static synchronized void enterReplaceSearch(String fromBlockId) {
        if (fromBlockId == null || fromBlockId.isEmpty()) return;
        replaceFrom = fromBlockId;
        replaceTo = "";
        searchBuffer = "";
        screen = Screen.REPLACE_SEARCH;
    }

    /** Pick the replacement — advances to the confirm band (same-block picks bounce back). */
    public static synchronized void chooseReplacement(String toBlockId) {
        if (toBlockId == null || toBlockId.isEmpty() || toBlockId.equals(replaceFrom)) return;
        replaceTo = toBlockId;
        screen = Screen.CONFIRM_REPLACE;
    }

    /** Back to the grid/parts root, dropping any in-flight replace state. */
    public static synchronized void backToRoot() {
        screen = Screen.ROOT;
        searchBuffer = "";
        replaceFrom = "";
        replaceTo = "";
    }

    public static synchronized void appendSearch(char c) {
        searchBuffer = searchBuffer + c;
    }

    public static synchronized void backspaceSearch() {
        if (!searchBuffer.isEmpty()) {
            searchBuffer = searchBuffer.substring(0, searchBuffer.length() - 1);
        }
    }

    /** Substring filter over the full block registry — reuses {@link BlockVariantMenu#allBlockIds()}. */
    public static List<String> filteredBlockIds() {
        List<String> all = BlockVariantMenu.allBlockIds();
        String needle = searchBuffer.toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return all;
        List<String> out = new ArrayList<>();
        for (String id : all) {
            if (id.contains(needle)) out.add(id);
        }
        return out;
    }

    // ---------- accessors ----------

    public static boolean isActive() { return active; }
    public static String stageId() { return stageId; }
    public static String stageName() { return stageName; }
    public static BlockPos anchor() { return anchor; }
    public static List<String> blocks() { return blocks; }
    /** The stage's REAL unique-block count — {@link #blocks()} is wire-capped below it. */
    public static int totalBlocks() { return totalBlocks; }
    public static List<StageBlocksSyncPacket.PartEntry> parts() { return parts; }
    public static boolean hideUnused() { return hideUnused; }
    public static Screen screen() { return screen; }
    public static String searchBuffer() { return searchBuffer; }
    public static String replaceFrom() { return replaceFrom; }
    public static String replaceTo() { return replaceTo; }
    public static Hit hovered() { return hovered; }
    public static void setHovered(Hit hit) { hovered = hit == null ? Hit.NONE : hit; }
}
