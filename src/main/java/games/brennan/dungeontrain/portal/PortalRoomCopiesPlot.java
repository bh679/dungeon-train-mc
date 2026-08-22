package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.VariantGroupResolver;
import games.brennan.dungeontrain.editor.VariantState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link PortalRoomCopiesVariant} presented as a {@link BlockVariantPlot} with exactly one cell, so
 * the Block Variant menu can author it.
 *
 * <h2>Why a plot and not a menu of its own</h2>
 * <p>The menu, its sync and edit packets, the clipboard item and the loot-prefab links all talk to
 * {@link BlockVariantPlot} and none of them knows which sidecar is underneath — that is what the
 * interface is for. Presenting this file as the fifth implementation buys the whole authoring
 * surface (Copy / Add / Remove / Clear, per-row weights, rotation modes, difficulty bands) at the
 * cost of the handful of one-liners below, and it means a variant copied here pastes onto an
 * ordinary cell and back.</p>
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

    /** The one address this plot has. */
    public static final BlockPos CELL = BlockPos.ZERO;

    private static final Vec3i FOOTPRINT = new Vec3i(1, 1, 1);

    private final String roomName;
    private final BlockPos origin;
    private PortalRoomCopiesVariant variant;

    public PortalRoomCopiesPlot(String roomName, BlockPos origin, PortalRoomCopiesVariant variant) {
        this.roomName = roomName;
        this.origin = origin;
        this.variant = variant;
    }

    /** The room whose Copies setting this is. */
    public String roomName() {
        return roomName;
    }

    /** True when {@code key} addresses a copies plot; {@link #roomOf} names which. */
    public static boolean isCopiesKey(String key) {
        return key != null && key.startsWith(KEY_PREFIX);
    }

    /** The room named by a copies key, or null when {@code key} is not one. */
    @Nullable
    public static String roomOf(String key) {
        return isCopiesKey(key) ? key.substring(KEY_PREFIX.length()) : null;
    }

    @Override
    public String key() {
        return KEY_PREFIX + roomName;
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
        if (!CELL.equals(localPos) || variant.isEmpty()) return null;
        return variant.states();
    }

    @Override
    public void put(BlockPos localPos, List<VariantState> states) {
        if (!CELL.equals(localPos)) return;
        variant = variant.withStates(states);
    }

    @Override
    public boolean remove(BlockPos localPos) {
        if (!CELL.equals(localPos) || variant.isEmpty()) return false;
        variant = PortalRoomCopiesVariant.empty();
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
        return variant.isEmpty() ? Set.of() : Set.of(CELL);
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
        if (!variant.isEmpty()) entries.put(CELL, variant.states());
        return new VariantGroupResolver(entries, new LinkedHashMap<>());
    }
}
