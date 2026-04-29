package games.brennan.dungeontrain.client.menu.containercontents;

import games.brennan.dungeontrain.net.ContainerContentsSyncPacket;
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
        ENTRY_NAME,
        ENTRY_COUNT_MINUS,
        ENTRY_COUNT_PLUS,
        ENTRY_WEIGHT_MINUS,
        ENTRY_WEIGHT_PLUS,
        ENTRY_REMOVE_X,
        SEARCH_FIELD,
        SEARCH_RESULT,
        SEARCH_BACK
    }

    public record Hit(CellKind kind, int index) {
        public static final Hit NONE = new Hit(CellKind.NONE, -1);
    }

    private ContainerContentsMenu() {}

    private static boolean active;
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

    private static Screen screen = Screen.ROOT;
    private static String searchBuffer = "";
    private static Hit hovered = Hit.NONE;

    @Nullable private static List<String> cachedItemIds;

    public static boolean isActive() { return active; }
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
    public static Screen screen() { return screen; }
    public static String searchBuffer() { return searchBuffer; }
    public static Hit hovered() { return hovered; }

    public static void setHovered(Hit h) { hovered = h == null ? Hit.NONE : h; }

    public static void applySync(ContainerContentsSyncPacket packet) {
        if (packet.localPos() == null) {
            active = false;
            localPos = null;
            entries = Collections.emptyList();
            screen = Screen.ROOT;
            searchBuffer = "";
            hovered = Hit.NONE;
            return;
        }
        boolean newCell = !packet.plotKey().equals(plotKey)
            || !packet.localPos().equals(localPos);
        active = true;
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
        if (newCell) {
            screen = Screen.ROOT;
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
