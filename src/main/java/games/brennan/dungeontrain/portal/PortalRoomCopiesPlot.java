package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.VariantGroupResolver;
import games.brennan.dungeontrain.editor.VariantState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One plane of a {@link PortalRoomCopiesVariant} presented as a {@link BlockVariantPlot} with
 * exactly one cell, so the Block Variant menu can author it.
 *
 * <h2>Why a plot and not a menu of its own</h2>
 * <p>The menu, its sync and edit packets, the clipboard item and the loot-prefab links all talk to
 * {@link BlockVariantPlot} and none of them knows which sidecar is underneath — that is what the
 * interface is for. Presenting this file as the fifth implementation buys the whole authoring
 * surface (Copy / Add / Remove / Clear, per-row weights, rotation modes, difficulty bands) at the
 * cost of the handful of one-liners below, and it means a variant copied here pastes onto an
 * ordinary cell and back.</p>
 *
 * <h2>One plane per plot, not one plot with two cells</h2>
 * <p>{@code BlockVariantMenuController.openForCopies} anchors its panel on a <b>single</b> cell and
 * the panel has no way to walk to another one, so a two-cell plot would leave the roof unreachable.
 * The plane goes in the plot's identity instead: {@link #key()} is {@code copies:<plane>:<room>},
 * and each editor row opens the plot for its own plane. Keys live in the menu's session map and in
 * its edit packets, never on disk, so there is nothing to migrate.</p>
 *
 * <h2>One cell, at the origin</h2>
 * <p>The footprint is {@code 1×1×1} and the only address is {@link BlockPos#ZERO}. The plot is not
 * anywhere in the world — it is a setting on a room — so {@link #origin} is the room plot's origin
 * only so the menu has somewhere to anchor its panel; nothing is ever read from or written to the
 * world through it.</p>
 *
 * <h2>What is deliberately inert</h2>
 * <p><b>Lock ids</b> do nothing: a lock group is how several cells agree on one roll, and there is
 * only ever one cell here. {@link PortalRoomCopiesVariant#resolve} passes {@code lockId 0} for the
 * same reason. <b>Mirror axes</b> do nothing: mirroring reflects a template across its own box, and
 * this value has no box. Both are answered rather than thrown from, because the menu asks every
 * plot these questions and a plot that threw would crash the panel rather than simply not offer the
 * button.</p>
 */
public final class PortalRoomCopiesPlot implements BlockVariantPlot {

    /** Key prefix, alongside {@code carriage:} / {@code contents:} / {@code part:} / {@code track:}. */
    public static final String KEY_PREFIX = "copies:";

    /** Separates the plane from the room inside a copies key: {@code copies:roof:deserter}. */
    private static final String KEY_SEPARATOR = ":";

    /** The one address this plot has. */
    public static final BlockPos CELL = BlockPos.ZERO;

    private static final Vec3i FOOTPRINT = new Vec3i(1, 1, 1);

    private final String roomName;
    private final PortalRoomCopiesVariant.Plane plane;
    private final BlockPos origin;
    private PortalRoomCopiesVariant variant;

    public PortalRoomCopiesPlot(String roomName, PortalRoomCopiesVariant.Plane plane,
                                BlockPos origin, PortalRoomCopiesVariant variant) {
        this.roomName = roomName;
        this.plane = plane == null ? PortalRoomCopiesVariant.Plane.FLOOR : plane;
        this.origin = origin;
        this.variant = variant;
    }

    /** The room whose Copies setting this is. */
    public String roomName() {
        return roomName;
    }

    /** Which of the room's two planes this plot authors. */
    public PortalRoomCopiesVariant.Plane plane() {
        return plane;
    }

    /** True when {@code key} addresses a copies plot; {@link #roomOf} names which. */
    public static boolean isCopiesKey(String key) {
        return key != null && key.startsWith(KEY_PREFIX);
    }

    /**
     * The room named by a copies key, or null when {@code key} is not one.
     *
     * <p>A key written before the planes were split — {@code copies:<room>} — names the room and no
     * plane, and reads back here as that room. {@link #planeOf} answers Floor for it, which is the
     * plane such a key always meant.</p>
     */
    @Nullable
    public static String roomOf(String key) {
        if (!isCopiesKey(key)) return null;
        String rest = key.substring(KEY_PREFIX.length());
        int cut = rest.indexOf(KEY_SEPARATOR);
        if (cut < 0) return rest;
        return PortalRoomCopiesVariant.Plane.parse(rest.substring(0, cut)) == null
            ? rest : rest.substring(cut + KEY_SEPARATOR.length());
    }

    /** The plane a copies key addresses — Floor for a key that names none, and for a non-key. */
    public static PortalRoomCopiesVariant.Plane planeOf(String key) {
        if (!isCopiesKey(key)) return PortalRoomCopiesVariant.Plane.FLOOR;
        String rest = key.substring(KEY_PREFIX.length());
        int cut = rest.indexOf(KEY_SEPARATOR);
        if (cut < 0) return PortalRoomCopiesVariant.Plane.FLOOR;
        PortalRoomCopiesVariant.Plane parsed =
            PortalRoomCopiesVariant.Plane.parse(rest.substring(0, cut));
        return parsed == null ? PortalRoomCopiesVariant.Plane.FLOOR : parsed;
    }

    @Override
    public String key() {
        return KEY_PREFIX + plane.id() + KEY_SEPARATOR + roomName;
    }

    @Override
    public BlockPos origin() {
        return origin;
    }

    @Override
    public Vec3i footprint() {
        return FOOTPRINT;
    }

    @Override
    @Nullable
    public List<VariantState> statesAt(BlockPos localPos) {
        if (!CELL.equals(localPos) || variant.isEmpty(plane)) return null;
        return variant.states(plane);
    }

    @Override
    public void put(BlockPos localPos, List<VariantState> states) {
        if (!CELL.equals(localPos)) return;
        variant = variant.withStates(plane, states);
    }

    @Override
    public boolean remove(BlockPos localPos) {
        if (!CELL.equals(localPos) || variant.isEmpty(plane)) return false;
        // This plane only. Clearing the roof must leave the floor standing, or an author tidying one
        // row would unbuild the plain.
        variant = variant.withStates(plane, List.of());
        return true;
    }

    /**
     * Persist, and in dev mode write through to the source tree.
     *
     * <p>The write-through lives here rather than at the call site because the Block Variant menu's
     * save path is generic — it calls {@code plot.save()} and knows nothing about which sidecar it
     * is driving. Every other plot's source propagation is triggered from an editor command that
     * does know; this one has no such command, so the plot owns it.</p>
     */
    @Override
    public void save() throws IOException {
        variant.save(roomName);
        if (games.brennan.dungeontrain.editor.EditorDevMode.isEnabled()) {
            variant.saveToSource(roomName);
        }
    }

    /**
     * This plot's sidecar, serialised as {@link #save} would write it — the
     * editor undo history's before/after snapshot for a copies edit.
     */
    @Override
    public String snapshotJson() {
        return variant.toJsonText();
    }

    /**
     * Put the sidecar file back to {@code json} and drop the cached instance, so
     * the next {@code loadFor} reads the restored document.
     */
    @Override
    public void restoreJson(String json) throws IOException {
        Path file = PortalRoomCopiesVariant.configPathFor(roomName);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
        PortalRoomCopiesVariant.invalidate(roomName);
    }

    /** The variant as it now stands — what the caller persists to the source tree in dev mode. */
    public PortalRoomCopiesVariant variant() {
        return variant;
    }

    // ---------- inert: a single cell has no group and no box ----------

    @Override public boolean mirrorX() { return false; }
    @Override public boolean mirrorY() { return false; }
    @Override public boolean mirrorZ() { return false; }
    @Override public boolean mirrorVariants() { return false; }
    @Override public void setMirrorAxes(boolean x, boolean y, boolean z) {}
    @Override public void setMirrorVariants(boolean v) {}

    @Override public int lockIdAt(BlockPos localPos) { return 0; }
    @Override public void setLockId(BlockPos localPos, int lockId) {}
    @Override public Set<BlockPos> positionsWithLockId(int lockId) { return Set.of(); }
    @Override public Map<BlockPos, Integer> allLockIds() { return Map.of(); }
    @Override public int nextFreeLockId() { return 1; }

    @Override
    public Set<BlockPos> allFlaggedPositions() {
        return variant.isEmpty(plane) ? Set.of() : Set.of(CELL);
    }

    /**
     * A resolver over this plot's one cell.
     *
     * <p>Built fresh rather than cached because {@link #put} replaces the variant wholesale, and a
     * resolver holding the previous list would answer the menu's reference questions about a value
     * that is no longer there.</p>
     */
    @Override
    public VariantGroupResolver groupRefs() {
        Map<BlockPos, List<VariantState>> entries = new LinkedHashMap<>();
        if (!variant.isEmpty(plane)) entries.put(CELL, variant.states(plane));
        return new VariantGroupResolver(entries, new LinkedHashMap<>());
    }
}
