package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-level registry of built hallway portals, persisted to
 * {@code <world>/data/dungeontrain_hallway_portals.dat}, following the same shape as
 * {@link games.brennan.dungeontrain.world.StairsRegistryData}.
 *
 * <p>Persistence is not optional bookkeeping here — it is what keeps the illusion's invariant
 * enforceable. {@code PortalTransitEvents} decides which copy a player belongs in by consulting
 * this registry; without it, a player who saves and quits inside the FAR copy would come back with
 * nothing to move them, stranded in a corridor whose far door opens onto a pocket that the world
 * around them no longer agrees with.</p>
 */
public final class PortalRegistry extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String NAME = "dungeontrain_hallway_portals";

    private static final String TAG_PORTALS = "portals";
    private static final String TAG_AUTO_SPACING = "autoSpacing";
    private static final String TAG_CARRIAGES_ENABLED = "carriagesEnabled";
    private static final String TAG_ONE_IN_GROUPS = "oneInGroups";
    private static final String TAG_ORIGIN_X = "originX";
    private static final String TAG_FLOOR_Y = "floorY";
    private static final String TAG_ORIGIN_Z = "originZ";
    private static final String TAG_LENGTH = "length";
    private static final String TAG_WIDTH = "width";
    private static final String TAG_HEIGHT = "height";
    private static final String TAG_DELTA_Y = "deltaY";

    private final List<PortalGeometry> portals = new ArrayList<>();

    /**
     * Anchor-grid spacing for auto-spawning, or {@link PortalAnchors#SPACING_OFF}. Persisted so the
     * setting survives a reload — otherwise a world would quietly stop spawning portals (or start
     * again) depending on when it was last saved.
     */
    private int autoSpacing = DEFAULT_AUTO_SPACING;

    /** Whether any carriage along the train is a portal corridor at all. */
    private boolean carriagesEnabled = true;

    /**
     * How rare portal groups are: on average one carriage group in this many. Persisted for the same
     * reason as the spacing above — a carriage's blocks are re-stamped whenever the rolling window
     * brings it round again, so a world that forgot this would re-decide which carriages are
     * corridors on every reload, and one could quietly stop being a portal under a player standing
     * in it.
     */
    private int oneInGroups = PortalCarriageSelection.DEFAULT_ONE_IN_GROUPS;

    /**
     * Default anchor spacing for the free-standing portals that generate beside the track.
     *
     * <p><b>Off.</b> They were the prototype — a pair of corridors in the world with a fixed vertical
     * offset — and the carriage portals have superseded them. Leaving them on meant portals kept
     * appearing beside and above the train alongside the real ones. The system stays in the codebase
     * as a working reference for the simpler stationary case; turn it back on per world with
     * {@code /dungeontrain portal auto <spacing>}.</p>
     *
     * <p>Note this only affects worlds that have not stored a spacing yet. A world already carrying
     * one keeps it — {@code /dungeontrain portal auto off} clears that.</p>
     */
    public static final int DEFAULT_AUTO_SPACING = PortalAnchors.SPACING_OFF;

    private PortalRegistry() {}

    public static PortalRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(
                PortalRegistry::new,
                (tag, registries) -> load(tag)
            ),
            NAME
        );
    }

    /** Immutable snapshot of the built portals, in build order. */
    public synchronized List<PortalGeometry> all() {
        return List.copyOf(portals);
    }

    public synchronized boolean isEmpty() {
        return portals.isEmpty();
    }

    public synchronized void add(PortalGeometry geo) {
        portals.add(geo);
        setDirty();
    }

    /** True if a portal has already been stamped with its corridor starting at {@code originX}. */
    public synchronized boolean hasPortalAt(int originX) {
        for (PortalGeometry geo : portals) {
            if (geo.originX() == originX) return true;
        }
        return false;
    }

    /** Anchor spacing for auto-spawning, or {@link PortalAnchors#SPACING_OFF} when disabled. */
    public synchronized int autoSpacing() {
        return autoSpacing;
    }

    public synchronized void setAutoSpacing(int spacing) {
        if (autoSpacing == spacing) return;
        autoSpacing = spacing;
        setDirty();
    }

    /**
     * Whether portal carriages are placed along the train at all. How often they occur is
     * {@link #oneInGroups()}; how far apart a pair's two carriages sit is not configurable — a
     * portal spans exactly one carriage group. See {@link PortalCarriageSelection}.
     */
    public synchronized boolean carriagesEnabled() {
        return carriagesEnabled;
    }

    public synchronized void setCarriagesEnabled(boolean enabled) {
        if (carriagesEnabled == enabled) return;
        carriagesEnabled = enabled;
        setDirty();
    }

    /** On average one carriage group in this many is a portal group. See {@link PortalCarriageSelection}. */
    public synchronized int oneInGroups() {
        return oneInGroups;
    }

    public synchronized void setOneInGroups(int groups) {
        if (oneInGroups == groups) return;
        oneInGroups = groups;
        setDirty();
    }

    /** Forget every portal, returning how many were dropped. Blocks already stamped are left alone. */
    public synchronized int clear() {
        int removed = portals.size();
        if (removed > 0) {
            portals.clear();
            setDirty();
        }
        return removed;
    }

    private static PortalRegistry load(CompoundTag tag) {
        PortalRegistry data = new PortalRegistry();
        if (tag.contains(TAG_AUTO_SPACING)) {
            data.autoSpacing = tag.getInt(TAG_AUTO_SPACING);
        }
        if (tag.contains(TAG_CARRIAGES_ENABLED)) {
            data.carriagesEnabled = tag.getBoolean(TAG_CARRIAGES_ENABLED);
        }
        // Absent in worlds saved before portal rarity existed, which is exactly the case that should
        // pick up the new default rather than the old every-group density.
        if (tag.contains(TAG_ONE_IN_GROUPS)) {
            data.oneInGroups = tag.getInt(TAG_ONE_IN_GROUPS);
        }
        if (!tag.contains(TAG_PORTALS)) return data;

        ListTag list = tag.getList(TAG_PORTALS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            try {
                data.portals.add(new PortalGeometry(
                    e.getInt(TAG_ORIGIN_X),
                    e.getInt(TAG_FLOOR_Y),
                    e.getInt(TAG_ORIGIN_Z),
                    e.getInt(TAG_LENGTH),
                    e.getInt(TAG_WIDTH),
                    e.getInt(TAG_HEIGHT),
                    e.getInt(TAG_DELTA_Y)
                ));
            } catch (IllegalArgumentException ex) {
                // PortalGeometry validates its own invariants, so a hand-edited or
                // older-format entry lands here. Skip it rather than failing the world load —
                // the worst case is one portal stops swapping, not an unopenable save.
                LOGGER.warn("[DungeonTrain] Skipping invalid hallway portal entry {}: {}", i, ex.getMessage());
            }
        }
        return data;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt(TAG_AUTO_SPACING, autoSpacing);
        tag.putBoolean(TAG_CARRIAGES_ENABLED, carriagesEnabled);
        tag.putInt(TAG_ONE_IN_GROUPS, oneInGroups);

        ListTag list = new ListTag();
        for (PortalGeometry geo : portals) {
            CompoundTag e = new CompoundTag();
            e.putInt(TAG_ORIGIN_X, geo.originX());
            e.putInt(TAG_FLOOR_Y, geo.floorY());
            e.putInt(TAG_ORIGIN_Z, geo.originZ());
            e.putInt(TAG_LENGTH, geo.length());
            e.putInt(TAG_WIDTH, geo.width());
            e.putInt(TAG_HEIGHT, geo.height());
            e.putInt(TAG_DELTA_Y, geo.deltaY());
            list.add(e);
        }
        tag.put(TAG_PORTALS, list);
        return tag;
    }
}
