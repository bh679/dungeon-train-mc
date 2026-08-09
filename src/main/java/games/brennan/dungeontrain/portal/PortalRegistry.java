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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private static final String TAG_SEVERED = "severed";

    private final List<PortalGeometry> portals = new ArrayList<>();

    /**
     * Carriage indices whose way <b>in</b> has been severed by a break in their corridor's outer
     * shell — see {@link PortalSever}.
     *
     * <p>Persisted because the severing is permanent. A carriage index is a fixed place along the
     * track, so this stays meaningful across a reload, across the pair being re-stamped when the
     * train rolls on, and across the world being reopened months later. Without persistence a
     * player could break the illusion, quit, and come back to a portal that had quietly forgiven
     * them.</p>
     *
     * <p>Stored rather than derived: the hole itself does not survive, because a corridor's blocks
     * are re-stamped from its template every time the rolling window brings it round again.</p>
     */
    private final Set<Integer> severedCarriages = new HashSet<>();

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

    /** True if this carriage's corridor no longer takes anyone in. The way out is never severed. */
    public synchronized boolean isSevered(int carriageIndex) {
        return severedCarriages.contains(carriageIndex);
    }

    /** Record a severing. Returns false if it was already severed, so the effects fire only once. */
    public synchronized boolean sever(int carriageIndex) {
        if (!severedCarriages.add(carriageIndex)) return false;
        setDirty();
        return true;
    }

    /** The severed carriage indices, ascending, for {@code /dungeontrain portal severed list}. */
    public synchronized List<Integer> severed() {
        return severedCarriages.stream().sorted().toList();
    }

    /** Repair every severed corridor, returning how many were restored. */
    public synchronized int clearSevered() {
        int restored = severedCarriages.size();
        if (restored > 0) {
            severedCarriages.clear();
            setDirty();
        }
        return restored;
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
        // Absent in worlds saved before severing existed, which read back as "nothing severed" —
        // the right answer for a world where no corridor had ever been broken into.
        for (int carriageIndex : tag.getIntArray(TAG_SEVERED)) {
            data.severedCarriages.add(carriageIndex);
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
        tag.putIntArray(TAG_SEVERED, severedCarriages.stream().mapToInt(Integer::intValue).toArray());

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
