package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
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
    private static final String TAG_CARRIAGE_EVERY_SET = "carriageEverySet";
    private static final String TAG_ORIGIN_X = "originX";
    private static final String TAG_FLOOR_Y = "floorY";
    private static final String TAG_ORIGIN_Z = "originZ";
    private static final String TAG_LENGTH = "length";
    private static final String TAG_WIDTH = "width";
    private static final String TAG_HEIGHT = "height";
    private static final String TAG_DELTA_Y = "deltaY";
    private static final String TAG_SEVERED = "severed";
    private static final String TAG_STAMPED_PORTAL_PARTS = "stampedPortalParts";

    private final List<PortalGeometry> portals = new ArrayList<>();

    /**
     * Portal <b>pairs</b> whose way <b>in</b> has been severed by a break in one of their corridors'
     * outer shell — see {@link PortalSever}.
     *
     * <p><b>One entry per pair, keyed on the group's anchor</b> ({@link PortalCarriageRole#entryIndexOf}),
     * never on the corridor that happened to be broken. A portal is a pair sharing one room, and it
     * is the pair that is severed; storing the two corridor indices separately and asking with a
     * third frame — whichever index the caller had in hand — is a shape that can only stay in step
     * by luck, and when it fell out of step the entrance was dead while the exit still took people
     * in. One key, asked one way, cannot disagree with itself.</p>
     *
     * <p><b>Persisted, but not permanent.</b> It has to be stored rather than re-derived from the
     * hole, because a corridor's blocks are re-stamped from its template every time the rolling
     * window brings it round again and the hole itself is gone within a minute. Persistence is what
     * keeps a player from breaking the illusion, quitting, and coming back to a portal that had
     * quietly forgiven them. But it lasts exactly as long as the damage: {@code CarriagePlacer}
     * calls {@link #repairPair} as it re-stamps a portal group, because the template it is about to
     * write restores the shell — and a pair that is whole again has nothing left to refuse for. A
     * record that outlived its blocks was how a dimensional carriage could stand there intact and
     * still lead nowhere for the rest of the world's life.</p>
     */
    private final Set<Integer> severedPairs = new HashSet<>();

    /**
     * Carriage indices that were actually <b>stamped</b> as part of a portal group — the two
     * corridors and the cart between them — as opposed to indices the selection lottery merely
     * believes should be.
     *
     * <p><b>Why the verdict has to be recorded rather than re-derived.</b>
     * {@link PortalCarriageSelection#rateFor} reads the level's live game modes, so the same
     * carriage index answers differently once a player switches to creative, joins, or quits. The
     * blocks do not change with it: they were stamped once, by whatever the verdict was at the time.
     * {@code PortalCarriageEvents} builds its swap plane every tick, and re-deriving there meant an
     * ordinary carriage could grow a portal frame it had no corridor for — and teleport whoever
     * walked down it into a pocket room. Recorded at stamp time and read back, the two can no longer
     * disagree.</p>
     *
     * <p>The same reasoning {@link PortalCarriageSelection#corridorKindFor} is memoised under, one
     * level up: that keeps a pair's corridor <i>shape</i> from moving mid-session, this keeps its
     * <i>existence</i> from moving.</p>
     *
     * <p>Cleared for an index the rolling window re-stamps as an ordinary carriage, so the record
     * always describes what is standing there now, not what once was.</p>
     */
    private final Set<Integer> stampedPortalParts = new HashSet<>();

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
     * True once someone has set the rate by hand, as opposed to inheriting the shipped default.
     *
     * <p>Load-bearing on a dev build only, and only in creative: that build substitutes its own dense
     * testing cadence for the world's rate, and it must not do so once a rate has been asked for
     * explicitly — otherwise {@code portal carriage 7} silently does nothing in the dev client, which
     * is the one place it most needs to be testable. See
     * {@link PortalCarriageSelection#rateFor}.</p>
     */
    private boolean carriageEverySet = false;

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
        // The "set by hand" flag is recorded even when the value is unchanged: asking for the rate
        // the world already had is still asking, and on a dev build it is how a tester turns the
        // substituted testing cadence off.
        boolean firstSet = !carriageEverySet;
        carriageEverySet = true;
        if (carriageEvery == every && !firstSet) return;
        carriageEvery = every;
        setDirty();
    }

    /** True once the rate was set by hand rather than inherited — see {@link #carriageEverySet}. */
    public synchronized boolean isCarriageEverySet() {
        return carriageEverySet;
    }

    /**
     * True if this pair takes nobody in any more — asked of the pair's key, which is its group's
     * anchor. Both of its corridors answer alike by construction. The way out is never severed.
     */
    public synchronized boolean isPairSevered(int pairKey) {
        return severedPairs.contains(pairKey);
    }

    /** Record a severing. Returns false if it was already severed, so the effects fire only once. */
    public synchronized boolean severPair(int pairKey) {
        if (!severedPairs.add(pairKey)) return false;
        setDirty();
        return true;
    }

    /**
     * Forget this pair's severing — its corridors are being re-stamped from their template, so the
     * hole that severed them is about to stop existing.
     *
     * @return true if the pair was severed until now, so the caller can say so once
     */
    public synchronized boolean repairPair(int pairKey) {
        if (!severedPairs.remove(pairKey)) return false;
        setDirty();
        return true;
    }

    /** The severed pair keys, ascending, for {@code /dungeontrain portal severed list}. */
    public synchronized List<Integer> severed() {
        return severedPairs.stream().sorted().toList();
    }

    /** Repair every severed pair, returning how many were restored. */
    public synchronized int clearSevered() {
        int restored = severedPairs.size();
        if (restored > 0) {
            severedPairs.clear();
            setDirty();
        }
        return restored;
    }

    /**
     * True if this carriage index was stamped as part of a portal group and still is — the
     * authoritative answer for anything running after placement. See {@link #stampedPortalParts}.
     */
    public synchronized boolean isStampedPortalPart(int carriageIndex) {
        return stampedPortalParts.contains(carriageIndex);
    }

    /**
     * Record what was just stamped at this carriage index.
     *
     * <p>Called from {@code CarriagePlacer} for <b>every</b> carriage it places, portal or not: the
     * negative is as load-bearing as the positive, because an index the rolling window brings back
     * round as an ordinary carriage must stop answering yes.</p>
     */
    public synchronized void noteStamped(int carriageIndex, boolean portalPart) {
        boolean changed = portalPart
            ? stampedPortalParts.add(carriageIndex)
            : stampedPortalParts.remove(carriageIndex);
        if (changed) setDirty();
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

    /** Package-private rather than private so {@code PortalRegistrySeveredPairsTest} can read a tag. */
    static PortalRegistry load(CompoundTag tag) {
        PortalRegistry data = new PortalRegistry();
        if (tag.contains(TAG_AUTO_SPACING)) {
            data.autoSpacing = tag.getInt(TAG_AUTO_SPACING);
        }
        if (tag.contains(TAG_CARRIAGE_EVERY)) {
            data.carriageEvery = tag.getInt(TAG_CARRIAGE_EVERY);
        }
        // Absent on worlds saved before the flag existed. Those stored a rate unconditionally, so
        // whether it was chosen or inherited is unknowable — false is the safe read, since it only
        // affects which cadence a dev build shows in creative.
        data.carriageEverySet = tag.getBoolean(TAG_CARRIAGE_EVERY_SET);
        // Absent in worlds saved before severing existed, which read back as "nothing severed" —
        // the right answer for a world where no corridor had ever been broken into.
        //
        // A world saved before this became a per-PAIR record carries both corridors of every severed
        // pair. One of those two is always the pair's own key, because a pair's key IS its entry
        // corridor's index — so keeping only the entries that sit in the entry slot reads the old
        // shape correctly and drops the partner rather than leaving a second key nothing ever asks
        // about. A group size changed since the save can strand an entry; the worst that costs is
        // one pair that has forgiven a break nobody remembers, which is the harmless direction.
        int groupSize = DungeonTrainConfig.getGroupSize();
        for (int carriageIndex : tag.getIntArray(TAG_SEVERED)) {
            if (PortalCarriageSelection.slotOf(carriageIndex, groupSize)
                != PortalCarriageSelection.SLOT_ENTRY) {
                continue;
            }
            data.severedPairs.add(carriageIndex);
        }
        // Absent in worlds saved before the stamp record existed. Those read back as "nothing
        // recorded", and PortalCarriageEvents confirms such a carriage against its own blocks once
        // before trusting it — see PortalStampRecord.
        for (int carriageIndex : tag.getIntArray(TAG_STAMPED_PORTAL_PARTS)) {
            data.stampedPortalParts.add(carriageIndex);
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
        tag.putBoolean(TAG_CARRIAGE_EVERY_SET, carriageEverySet);
        tag.putIntArray(TAG_SEVERED, severedPairs.stream().mapToInt(Integer::intValue).toArray());
        tag.putIntArray(TAG_STAMPED_PORTAL_PARTS,
            stampedPortalParts.stream().mapToInt(Integer::intValue).toArray());

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
