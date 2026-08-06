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
    private static final String TAG_CARRIAGE_EVERY = "carriageEvery";
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

    /**
     * Every nth carriage is a portal corridor. Persisted for the same reason as the spacing above:
     * a carriage's blocks are re-stamped whenever the rolling window brings it round again, so this
     * has to give the same answer after a reload or a corridor would quietly become an ordinary
     * carriage under a player standing in it.
     */
    private int carriageEvery = PortalCarriageSelection.DEFAULT_CARRIAGE_EVERY;

    /**
     * Default anchor spacing, in blocks. Tuned for testing rather than play: at the default train
     * speed of 2 m/s this is roughly a portal a minute. See the Gate 3 notes — shipping this on by
     * default is a gameplay decision that has not been made.
     */
    public static final int DEFAULT_AUTO_SPACING = 128;

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
     * Every nth carriage along the train is stamped as a portal corridor, or
     * {@link PortalCarriageSelection#CARRIAGE_EVERY_OFF} for none.
     */
    public synchronized int carriageEvery() {
        return carriageEvery;
    }

    public synchronized void setCarriageEvery(int every) {
        if (carriageEvery == every) return;
        carriageEvery = every;
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
        if (tag.contains(TAG_CARRIAGE_EVERY)) {
            data.carriageEvery = tag.getInt(TAG_CARRIAGE_EVERY);
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
        tag.putInt(TAG_CARRIAGE_EVERY, carriageEvery);

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
