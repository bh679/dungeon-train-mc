package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.net.BlockVariantSyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Client singleton state for the block-variant world-space menu. Mirrors
 * {@link games.brennan.dungeontrain.client.menu.parts.PartPositionMenu}.
 *
 * <p>Driven by {@link BlockVariantSyncPacket} from the server: the server
 * pushes a sync when the player taps Z on a flagged block, and pushes
 * empty (close) when the player toggles off or the cell vanishes.</p>
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@link Screen#ROOT} — entries grid (state-name + weight + lock)
 *       with toolbar Copy / Add / Remove / Clear / X.</li>
 *   <li>{@link Screen#ADD_SEARCH} — typing buffer + filtered list of
 *       block IDs. Click a result → server appends.</li>
 * </ul>
 * Remove-mode is a flag on the ROOT screen (mirrors part menu) — when on,
 * each row gets a red X cell that confirms removal.</p>
 *
 * <p>The search registry is built locally from
 * {@link BuiltInRegistries#BLOCK} since vanilla guarantees identical
 * registries on both sides — no need to ship the list over the wire.</p>
 */
public final class BlockVariantMenu {

    /** Maximum rows per column before the grid wraps to a new column. */
    public static final int ROWS_PER_COLUMN = 10;

    public enum Screen { ROOT, ADD_SEARCH }

    public enum CellKind {
        NONE,
        COPY,
        ADD,
        REMOVE,
        CLEAR,
        LOCK,
        CLOSE,
        ENTRY_NAME,
        ENTRY_WEIGHT,
        ENTRY_REMOVE_X,
        SEARCH_FIELD,
        SEARCH_RESULT,
        SEARCH_BACK
    }

    /** A specific cell hit by the raycast. */
    public record Hit(CellKind kind, int index) {
        public static final Hit NONE = new Hit(CellKind.NONE, -1);
    }

    private BlockVariantMenu() {}

    private static boolean active;
    private static String variantId = "";
    @Nullable private static BlockPos localPos;
    private static List<BlockVariantSyncPacket.Entry> entries = Collections.emptyList();
    private static int lockId = 0;
    private static Vec3 anchorPos = Vec3.ZERO;
    private static Vec3 anchorRight = new Vec3(1, 0, 0);
    private static Vec3 anchorUp = new Vec3(0, 1, 0);
    private static Vec3 anchorNormal = new Vec3(0, 0, 1);

    private static Screen screen = Screen.ROOT;
    private static boolean removeMode;
    private static String searchBuffer = "";

    private static Hit hovered = Hit.NONE;

    /** Cached registry list — built lazily on first ADD_SEARCH entry. */
    @Nullable private static List<String> cachedBlockIds;

    public static boolean isActive() { return active; }
    public static String variantId() { return variantId; }
    @Nullable public static BlockPos localPos() { return localPos; }
    public static List<BlockVariantSyncPacket.Entry> entries() { return entries; }
    public static int lockId() { return lockId; }
    public static Vec3 anchorPos() { return anchorPos; }
    public static Vec3 anchorRight() { return anchorRight; }
    public static Vec3 anchorUp() { return anchorUp; }
    public static Vec3 anchorNormal() { return anchorNormal; }
    public static Screen screen() { return screen; }
    public static boolean removeMode() { return removeMode; }
    public static String searchBuffer() { return searchBuffer; }
    public static Hit hovered() { return hovered; }

    public static void setHovered(Hit h) {
        hovered = h == null ? Hit.NONE : h;
    }

    /**
     * Apply a server-pushed sync packet. {@code localPos == null} closes
     * the menu and resets sub-screen state so the next open lands on ROOT.
     */
    public static void applySync(BlockVariantSyncPacket packet) {
        if (packet.localPos() == null) {
            active = false;
            localPos = null;
            entries = Collections.emptyList();
            lockId = 0;
            screen = Screen.ROOT;
            removeMode = false;
            searchBuffer = "";
            hovered = Hit.NONE;
            return;
        }
        boolean newCell = !packet.variantId().equals(variantId)
            || !packet.localPos().equals(localPos);
        active = true;
        variantId = packet.variantId();
        localPos = packet.localPos();
        entries = List.copyOf(packet.entries());
        lockId = packet.lockId();
        anchorPos = packet.anchorPos();
        anchorRight = packet.anchorRight();
        anchorUp = packet.anchorUp();
        anchorNormal = anchorRight.cross(anchorUp).normalize();
        if (newCell) {
            screen = Screen.ROOT;
            removeMode = false;
            searchBuffer = "";
        }
        hovered = Hit.NONE;
    }

    public static void enterSearch() {
        screen = Screen.ADD_SEARCH;
        searchBuffer = "";
        hovered = Hit.NONE;
    }

    public static void backToRoot() {
        screen = Screen.ROOT;
        searchBuffer = "";
        hovered = Hit.NONE;
    }

    public static void toggleRemoveMode() {
        removeMode = !removeMode;
        hovered = Hit.NONE;
    }

    public static void appendSearch(char c) {
        if (searchBuffer.length() >= 32) return;
        searchBuffer = searchBuffer + c;
    }

    public static void backspaceSearch() {
        if (searchBuffer.isEmpty()) return;
        searchBuffer = searchBuffer.substring(0, searchBuffer.length() - 1);
    }

    /**
     * All registered block IDs, lower-cased, sorted alphabetically. Built
     * once and cached. Used by the ADD_SEARCH sub-screen.
     */
    public static List<String> allBlockIds() {
        if (cachedBlockIds == null) {
            List<String> ids = new ArrayList<>(BuiltInRegistries.BLOCK.keySet().size());
            for (ResourceLocation loc : BuiltInRegistries.BLOCK.keySet()) {
                ids.add(loc.toString());
            }
            Collections.sort(ids);
            cachedBlockIds = Collections.unmodifiableList(ids);
        }
        return cachedBlockIds;
    }

    /** Lowercase substring filter against {@link #allBlockIds()}. Empty buffer → all ids. */
    public static List<String> filteredBlockIds() {
        List<String> all = allBlockIds();
        if (searchBuffer.isEmpty()) return all;
        String needle = searchBuffer.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String n : all) {
            if (n.toLowerCase(Locale.ROOT).contains(needle)) out.add(n);
        }
        return out;
    }
}
