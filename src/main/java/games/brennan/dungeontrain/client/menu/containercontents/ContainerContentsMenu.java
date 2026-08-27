package games.brennan.dungeontrain.client.menu.containercontents;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.EditorMenuSpace;
import games.brennan.dungeontrain.net.ContainerContentsSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Client singleton state for the container-contents world-space menu.
 * Mirrors {@link games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenu}
 * with a simpler feature set — no rotation, no lock-IDs, item registry instead
 * of block registry.
 */
public final class ContainerContentsMenu {

    public static final int ROWS_PER_COLUMN = 10;

    public enum Screen { ROOT, ADD_SEARCH }

    public enum CellKind {
        NONE,
        ADD,
        SAVE,
        FILL_MIN,
        FILL_MAX,
        CLEAR,
        CLOSE,
        /**
         * The item icon at the left of an entry row. Clicking toggles that row's
         * expansion, which reveals the item's name beside the icon.
         */
        ENTRY_ICON,
        ENTRY_COUNT_MINUS,
        ENTRY_COUNT_PLUS,
        ENTRY_WEIGHT_MINUS,
        ENTRY_WEIGHT_PLUS,
        ENTRY_REMOVE_X,
        /** Per-entry sub-row checkbox: flip random-durability master toggle. */
        ENTRY_RAND_DUR_TOGGLE,
        /** Per-entry sub-row %NN cell: bump random-durability chance (0-100). */
        ENTRY_DUR_CHANCE,
        /** Per-entry sub-row checkbox: flip random-enchantment master toggle. */
        ENTRY_RAND_ENCH_TOGGLE,
        /** Per-entry sub-row %NN cell: bump random-enchantment chance (0-100). */
        ENTRY_ENCH_CHANCE,
        /** Per-entry main-row cell: cycle slot override (auto / in / fuel / out). */
        ENTRY_SLOT_ASSIGN,
        SEARCH_FIELD,
        SEARCH_RESULT,
        SEARCH_BACK,
        /** Whole link sub-row hover (informational). */
        LINK_INDICATOR,
        /** The 'X' on the link sub-row — click to unlink. */
        LINK_UNLINK
    }

    public record Hit(CellKind kind, int index) {
        public static final Hit NONE = new Hit(CellKind.NONE, -1);
    }

    private ContainerContentsMenu() {}

    private static boolean active;
    /**
     * Where this opening of the menu draws, latched when the server activates it rather than
     * read live. The two modes tear down differently — a Screen to pop versus an event
     * subscriber to stop drawing — so the menu must close in the mode it opened in.
     */
    private static EditorMenuSpace space = EditorMenuSpace.DEFAULT;
    private static String plotKey = "";
    @Nullable private static BlockPos localPos;
    private static List<ContainerContentsSyncPacket.Entry> entries = Collections.emptyList();
    private static int fillMin = 0;
    private static int fillMax = -1;
    private static int containerSize = 0;
    private static Vec3 anchorPos = Vec3.ZERO;
    private static Vec3 anchorRight = new Vec3(1, 0, 0);
    private static Vec3 anchorUp = new Vec3(0, 1, 0);
    private static Vec3 anchorNormal = new Vec3(0, 0, 1);
    @Nullable private static String linkedPrefabId;

    /**
     * Entry indices whose name is currently revealed beside the icon. Cleared
     * whenever the entry list changes size, because a removal shifts every later
     * index and a stale index would expand the wrong row. Same-size edits (count,
     * weight, slot, chance bumps) keep the expansion.
     */
    private static final Set<Integer> expandedRows = new HashSet<>();

    private static Screen screen = Screen.ROOT;
    private static String searchBuffer = "";
    private static Hit hovered = Hit.NONE;

    @Nullable private static List<String> cachedItemIds;

    public static boolean isActive() { return active; }

    /** Where this opening of the menu draws. See {@link #space}. */
    public static EditorMenuSpace space() { return space; }

    /**
     * Active <em>and</em> drawing in the world — the guard the world-space renderer and input
     * handler use. In screen-space {@link ContainerContentsMenuScreen} owns rendering and input,
     * and both paths running would double-draw and double-hit-test.
     */
    public static boolean isActiveWorldspace() { return active && space.isWorldspace(); }
    public static String plotKey() { return plotKey; }
    @Nullable public static BlockPos localPos() { return localPos; }
    public static List<ContainerContentsSyncPacket.Entry> entries() { return entries; }
    public static int fillMin() { return fillMin; }
    public static int fillMax() { return fillMax; }
    public static int containerSize() { return containerSize; }
    public static Vec3 anchorPos() { return anchorPos; }
    public static Vec3 anchorRight() { return anchorRight; }
    public static Vec3 anchorUp() { return anchorUp; }
    public static Vec3 anchorNormal() { return anchorNormal; }
    @Nullable public static String linkedPrefabId() { return linkedPrefabId; }
    public static Screen screen() { return screen; }
    public static String searchBuffer() { return searchBuffer; }
    public static Hit hovered() { return hovered; }

    public static void setHovered(Hit h) { hovered = h == null ? Hit.NONE : h; }

    /** True when entry {@code index}'s name is revealed beside its icon. */
    public static boolean isExpanded(int index) { return expandedRows.contains(index); }

    /** Reveal / hide the name for entry {@code index}. Purely client-side. */
    public static void toggleExpanded(int index) {
        if (!expandedRows.remove(index)) expandedRows.add(index);
    }

    public static void applySync(ContainerContentsSyncPacket packet) {
        if (packet.localPos() == null) {
            dismissScreen();
            active = false;
            localPos = null;
            entries = Collections.emptyList();
            linkedPrefabId = null;
            screen = Screen.ROOT;
            searchBuffer = "";
            hovered = Hit.NONE;
            expandedRows.clear();
            return;
        }
        boolean newCell = !packet.plotKey().equals(plotKey)
            || !packet.localPos().equals(localPos);
        boolean sizeChanged = packet.entries().size() != entries.size();
        boolean wasActive = active;
        active = true;
        if (!wasActive) {
            space = ClientDisplayConfig.getContainerContentsMenuSpace();
        }
        plotKey = packet.plotKey();
        localPos = packet.localPos();
        entries = List.copyOf(packet.entries());
        fillMin = packet.fillMin();
        fillMax = packet.fillMax();
        containerSize = packet.containerSize();
        anchorPos = packet.anchorPos();
        anchorRight = packet.anchorRight();
        anchorUp = packet.anchorUp();
        anchorNormal = anchorRight.cross(anchorUp).normalize();
        linkedPrefabId = packet.linkedPrefabId();
        if (newCell) {
            screen = Screen.ROOT;
            searchBuffer = "";
        }
        if (newCell || sizeChanged) expandedRows.clear();
        hovered = Hit.NONE;
        // Re-pushed after every edit so the rows stay live, so only the first sync of an opening
        // puts the screen up; the ones after it just refresh what it is drawing.
        if (!wasActive && space.isScreenspace()) {
            Minecraft.getInstance().setScreen(new ContainerContentsMenuScreen());
        }
    }

    /** Pop our screen if it is the active one — guarded so another mod's GUI isn't clobbered. */
    private static void dismissScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof ContainerContentsMenuScreen)) return;
        try {
            mc.setScreen(null);
        } catch (IllegalStateException disconnectRace) {
            // setScreen throws during world disconnect ("Trying to return to in-game GUI during
            // disconnection"). MC is tearing the screen down anyway, so our state is already clean.
        }
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

    public static void appendSearch(char c) {
        if (searchBuffer.length() >= 32) return;
        searchBuffer = searchBuffer + c;
    }

    public static void backspaceSearch() {
        if (searchBuffer.isEmpty()) return;
        searchBuffer = searchBuffer.substring(0, searchBuffer.length() - 1);
    }

    public static List<String> allItemIds() {
        if (cachedItemIds == null) {
            List<String> ids = new ArrayList<>(BuiltInRegistries.ITEM.keySet().size());
            for (ResourceLocation loc : BuiltInRegistries.ITEM.keySet()) {
                ids.add(loc.toString());
            }
            Collections.sort(ids);
            cachedItemIds = Collections.unmodifiableList(ids);
        }
        return cachedItemIds;
    }

    public static List<String> filteredItemIds() {
        List<String> all = allItemIds();
        if (searchBuffer.isEmpty()) return all;
        String needle = searchBuffer.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String n : all) {
            if (n.toLowerCase(Locale.ROOT).contains(needle)) out.add(n);
        }
        return out;
    }
}
