package games.brennan.dungeontrain.client.menu.stagepanel;

import games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenu;
import games.brennan.dungeontrain.net.StageBlocksSyncPacket;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Locale;

/**
 * Client-side singleton state for the Stage Blocks panel (the "stage V menu") — the sibling
 * billboard beside the Stages panel showing the selected stage's usage-ordered block list (with a
 * count column, like #636's V menu), its parts list (each with its own icon strip), and the
 * Duplicate / Hide-unused / Close toolbar. Fed by {@link StageBlocksSyncPacket}; ops go back via
 * {@link games.brennan.dungeontrain.net.StagePanelEditPacket}. Mirrors {@link BlockVariantMenu}'s
 * state-singleton shape.
 *
 * <p>Single screen: clicking a block row swaps that block across the whole stage with the player's
 * <b>held block</b> (immediate, no search/confirm — reuses #636's swap-from-hand).</p>
 */
public final class StagePanelMenu {

    /** What a raycast hit resolves to. {@code index}/{@code secondary} meaning depends on kind. */
    public enum CellKind {
        NONE,
        /** Toolbar: opens the duplicate-name typing screen. */
        DUPLICATE,
        /** Toolbar: toggles the hide-unused-parts filter (server round-trip). */
        HIDE_TOGGLE,
        /** Toolbar: closes the panel. */
        CLOSE,
        /** A block list row — {@code index} into {@link #blocks()}. Click swaps it with the held block. */
        BLOCK_ROW,
        /** A part-row icon — {@code index} = part row, {@code secondary} = icon index in its strip. */
        PART_BLOCK
    }

    public record Hit(CellKind kind, int index, int secondary) {
        public static final Hit NONE = new Hit(CellKind.NONE, -1, -1);
    }

    private static boolean active = false;
    private static String stageId = "";
    private static String stageName = "";
    private static BlockPos anchor = BlockPos.ZERO;
    private static List<StageBlocksSyncPacket.BlockCount> blocks = List.of();
    private static int totalBlocks = 0;
    private static List<StageBlocksSyncPacket.PartEntry> parts = List.of();
    private static boolean hideUnused = false;
    private static volatile Hit hovered = Hit.NONE;

    private StagePanelMenu() {}

    /** Apply a server snapshot. {@code open == false} closes. */
    public static synchronized void applySync(StageBlocksSyncPacket packet) {
        if (!packet.open()) {
            closeLocal();
            return;
        }
        active = true;
        stageId = packet.stageId().toLowerCase(Locale.ROOT);
        stageName = packet.stageName();
        anchor = packet.anchorPos();
        blocks = packet.blocks();
        totalBlocks = packet.totalBlocks();
        parts = packet.parts();
        hideUnused = packet.hideUnused();
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
        hovered = Hit.NONE;
    }

    // ---------- accessors ----------

    public static boolean isActive() { return active; }
    public static String stageId() { return stageId; }
    public static String stageName() { return stageName; }
    public static BlockPos anchor() { return anchor; }
    public static List<StageBlocksSyncPacket.BlockCount> blocks() { return blocks; }
    /** The stage's REAL unique-block count — {@link #blocks()} is wire-capped below it. */
    public static int totalBlocks() { return totalBlocks; }
    public static List<StageBlocksSyncPacket.PartEntry> parts() { return parts; }
    public static boolean hideUnused() { return hideUnused; }
    public static Hit hovered() { return hovered; }
    public static void setHovered(Hit hit) { hovered = hit == null ? Hit.NONE : hit; }
}
