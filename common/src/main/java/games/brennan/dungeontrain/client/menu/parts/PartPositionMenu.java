package games.brennan.dungeontrain.client.menu.parts;

import games.brennan.dungeontrain.net.PartAssignmentSyncPacket;
import games.brennan.dungeontrain.train.CarriagePartAssignment.WeightedName;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * Client singleton state for the part-position world-space menu.
 *
 * <p>Driven by {@link PartAssignmentSyncPacket} from the server: the
 * server tracks which (variantId, kind) the player is hovering and
 * pushes a sync whenever it changes. The client renders whatever is
 * currently set; an empty state (kind == null) means "do not render".</p>
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@link Screen#ROOT} — name + weight grid + Add/Remove/Clear toolbar.</li>
 *   <li>{@link Screen#ADD_SEARCH} — typing buffer + filtered grid of
 *       registered names. Click a name → server adds it.</li>
 * </ul></p>
 *
 * <p>Remove-mode is a flag on the ROOT screen — when on, each entry row
 * gets a red [X] cell that confirms removal of that single entry.</p>
 */
public final class PartPositionMenu {

    /** Maximum rows per column before the grid wraps to a new column. */
    public static final int ROWS_PER_COLUMN = 10;

    public enum Screen { ROOT, ADD_SEARCH }

    public enum CellKind {
        NONE,
        ADD,
        REMOVE,
        CLEAR,
        CLOSE,
        ENTRY_NAME,
        ENTRY_WEIGHT,
        /** Per-entry Stage selector — click opens the Stage / Custom picker (chip when linked). */
        ENTRY_STAGE,
        ENTRY_MIN_LEVEL,
        ENTRY_MAX_LEVEL,
        ENTRY_PHASE,
        ENTRY_SIDE_MODE,
        ENTRY_END_MODE,
        ENTRY_REMOVE_X,
        SEARCH_FIELD,
        SEARCH_RESULT,
        SEARCH_BACK
    }

    /** True for kinds where the side-mode cell is rendered and interactive (walls / doors). */
    public static boolean kindHasSideMode(CarriagePartKind kind) {
        return kind == CarriagePartKind.WALLS || kind == CarriagePartKind.DOORS;
    }

    /** True for kinds where the end-mode cell is rendered and interactive (doors only). */
    public static boolean kindHasEndMode(CarriagePartKind kind) {
        return kind == CarriagePartKind.DOORS;
    }

    /**
     * A specific cell hit by the raycast. {@code phaseSlot} identifies which of the four phase
     * letters (0=O .. 3=E, matching {@link games.brennan.dungeontrain.worldgen.TrainPhase}'s ordinal)
     * an {@link CellKind#ENTRY_PHASE} hit landed on; it is {@code -1} for every other cell.
     */
    public record Hit(CellKind kind, int index, int phaseSlot) {
        public static final Hit NONE = new Hit(CellKind.NONE, -1, -1);

        /** Non-phase cells — {@code phaseSlot} defaults to {@code -1}. */
        public Hit(CellKind kind, int index) {
            this(kind, index, -1);
        }
    }

    private PartPositionMenu() {}

    private static boolean active;
    private static String variantId = "";
    @Nullable private static CarriagePartKind kind;
    private static List<WeightedName> entries = Collections.emptyList();
    private static List<String> registeredNames = Collections.emptyList();
    private static Vec3 anchorPos = Vec3.ZERO;
    private static Vec3 anchorRight = new Vec3(1, 0, 0);
    private static Vec3 anchorUp = new Vec3(0, 1, 0);
    private static Vec3 anchorNormal = new Vec3(0, 0, 1);

    private static Screen screen = Screen.ROOT;
    private static boolean removeMode;
    private static String searchBuffer = "";

    private static Hit hovered = Hit.NONE;

    public static boolean isActive() { return active; }
    public static String variantId() { return variantId; }
    @Nullable public static CarriagePartKind kind() { return kind; }
    public static List<WeightedName> entries() { return entries; }
    public static List<String> registeredNames() { return registeredNames; }
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
     * Reset to the "menu closed" state. Mirrors the {@code kind == null}
     * branch of {@link #applySync} but doesn't require a packet — invoked on
     * client logout so the singleton doesn't carry an active part-position
     * menu across worlds.
     */
    public static void clearForLogout() {
        active = false;
        kind = null;
        entries = Collections.emptyList();
        registeredNames = Collections.emptyList();
        screen = Screen.ROOT;
        removeMode = false;
        searchBuffer = "";
        hovered = Hit.NONE;
    }

    /**
     * Apply a server-pushed sync packet. {@code kind == null} closes the
     * menu and resets sub-screen state so the next open lands on ROOT.
     */
    public static void applySync(PartAssignmentSyncPacket packet) {
        if (packet.kind() == null) {
            active = false;
            kind = null;
            entries = Collections.emptyList();
            registeredNames = Collections.emptyList();
            screen = Screen.ROOT;
            removeMode = false;
            searchBuffer = "";
            hovered = Hit.NONE;
            return;
        }
        boolean newVariantOrKind = !packet.variantId().equals(variantId)
            || packet.kind() != kind;
        active = true;
        variantId = packet.variantId();
        kind = packet.kind();
        entries = List.copyOf(packet.entries());
        registeredNames = List.copyOf(packet.registeredNames());
        anchorPos = packet.anchorPos();
        anchorRight = packet.anchorRight();
        anchorUp = packet.anchorUp();
        // Right-handed basis: right × up = normal.
        anchorNormal = anchorRight.cross(anchorUp).normalize();
        if (newVariantOrKind) {
            // Reset sub-screen on hover transition; mid-edit re-syncs keep state.
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
     * Lowercase substring filter against {@link #registeredNames}, with names
     * already present in {@link #entries} excluded (the player can't add the
     * same variant twice — server enforces this too). Empty buffer → all
     * non-already-added names.
     */
    public static List<String> filteredRegisteredNames() {
        java.util.Set<String> alreadyAdded = new java.util.HashSet<>(entries.size());
        for (WeightedName e : entries) alreadyAdded.add(e.name());
        String needle = searchBuffer.isEmpty()
            ? null
            : searchBuffer.toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String n : registeredNames) {
            String lower = n.toLowerCase(java.util.Locale.ROOT);
            if (alreadyAdded.contains(lower)) continue;
            if (needle != null && !lower.contains(needle)) continue;
            out.add(n);
        }
        return out;
    }
}
