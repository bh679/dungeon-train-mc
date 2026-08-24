package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.ship.CarriageDeck;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * What is actually <b>standing</b> on the train, as opposed to what the selection lottery currently
 * believes should be — the authority for everything that runs after a carriage has been placed.
 *
 * <p><b>Why the two can differ at all.</b> {@link PortalCarriageSelection#rateFor} folds the level's
 * live game modes into the draw: while everyone on a level is in creative it returns an exact
 * cadence, otherwise the seeded lottery. Those two pick entirely different group ordinals, so the
 * verdict for a given carriage index changes the moment somebody switches mode, joins, or quits —
 * and the world's stored rate can be moved by hand as well. The blocks do not follow: they were
 * stamped once, by whatever the verdict was at the time.</p>
 *
 * <p><b>What that cost before this class existed.</b> {@code PortalCarriageEvents} re-derived the
 * verdict every tick and built a corridor swap plane from it. After a flip it claimed carriages that
 * had been stamped as ordinary ones — and the swap plane covers a whole carriage interior, so simply
 * walking or flying down a normal carriage teleported the player into a pocket room. Reported from a
 * creative fly-through: "I found myself in portal rooms, without going through a portal carriage."</p>
 *
 * <p><b>The rule.</b> {@code CarriagePlacer} records what it stamped
 * ({@link PortalRegistry#noteStamped}); readers ask the record. An index with no record is not a
 * portal, with one exception, below.</p>
 *
 * <p><b>The exception: worlds saved before the record existed.</b> Their standing portal carriages
 * carry no record and would go inert — including under a player logged out inside a pocket room. So
 * where the lottery says portal and nothing is recorded, the carriage is asked to prove it, by
 * reading its own Sable sub-level for the crossing-zone lanterns only a corridor has
 * ({@link PortalCarriageBuilder#stateAt}). A carriage that proves it is recorded there and then and
 * never asked again; one that cannot is refused, which is exactly the disagreement this class exists
 * to stop. Self-healing, so there is no migration step — and short-lived either way, since the
 * rolling window re-stamps every carriage it brings round.</p>
 */
public final class PortalStampRecord {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * How often an unrecorded group may be asked to prove itself, in ticks.
     *
     * <p>A confirmation that succeeds writes a record and is never repeated. One that fails is the
     * bug case — the lottery claiming a group that holds no corridor — and that repeats every tick
     * for as long as the group is near a player, so it is worth not re-reading the plot each time.
     * A second is far below the time it takes to walk a carriage, so nothing is kept waiting.</p>
     */
    private static final int CONFIRM_PERIOD_TICKS = 20;

    /** Next game time each group anchor may be re-confirmed at. Session state; see the period above. */
    private static final Map<Integer, Long> NEXT_CONFIRM = new HashMap<>();

    private PortalStampRecord() {}

    /** What is known about a group, before any blocks have been looked at. */
    public enum Verdict {
        /** Recorded as stamped: it holds a portal, and no live input can change that. */
        PORTAL,
        /** Nothing recorded and the lottery does not claim it either. Not a portal. */
        ORDINARY,
        /** Nothing recorded but the lottery claims it — {@link #confirmGroup} decides. */
        UNCONFIRMED
    }

    /** Drop the session's confirmation throttle — server shutdown, so the next world starts clean. */
    public static void reset() {
        NEXT_CONFIRM.clear();
    }

    /**
     * What is known about the group this carriage index belongs to.
     *
     * <p>Any one of the group's three indices being recorded answers for the whole group, the same
     * way {@code isPortalGroup} does: a portal is one group, stamped in one pass.</p>
     */
    public static Verdict groupVerdict(ServerLevel level, int carriageIndex, int groupSize) {
        int anchor = PortalCarriageSelection.groupAnchorOf(carriageIndex, groupSize);
        PortalRegistry registry = PortalRegistry.get(level);
        for (int slot = 0; slot < groupSize; slot++) {
            if (registry.isStampedPortalPart(anchor + slot)) return Verdict.PORTAL;
        }
        return PortalCarriageSelection.isPortalGroup(level, carriageIndex)
            ? Verdict.UNCONFIRMED
            : Verdict.ORDINARY;
    }

    /** True if this index holds one of the pair's two corridors — the carriages a swap plane covers. */
    public static boolean isStampedPortalCarriage(ServerLevel level, int carriageIndex,
                                                  int groupSize) {
        return PortalRegistry.get(level).isStampedPortalPart(carriageIndex)
            && isCorridorSlot(carriageIndex, groupSize);
    }

    /**
     * Which of a portal group's three carriages this index is — the two corridors, or the cart
     * between them.
     *
     * <p>Split from the record lookups above so it can be unit-tested, and kept as plain slot
     * arithmetic on purpose: the record says <i>whether</i> the group is a portal, which is the part
     * that used to move; <i>where within it</i> a carriage sits never did.</p>
     */
    public static boolean isCorridorSlot(int carriageIndex, int groupSize) {
        int slot = PortalCarriageSelection.slotOf(carriageIndex, groupSize);
        return slot == PortalCarriageSelection.SLOT_ENTRY
            || slot == PortalCarriageSelection.SLOT_EXIT;
    }

    /**
     * Ask an unrecorded group to prove it holds a corridor, and record it if it does.
     *
     * <p>Reads the <b>entry</b> corridor's crossing-zone floor, where a corridor and only a corridor
     * has sea lanterns ({@link PortalCarriageBuilder#stateAt}) — an ordinary carriage's floor is its
     * template's, whatever that is. Every interior cell of the zone is tried, so a player who has
     * mined one lantern out of a standing corridor does not cost the pair its portal.</p>
     *
     * <p>Throttled per group; see {@link #CONFIRM_PERIOD_TICKS}. A throttled call answers
     * {@code false}, which reads as "not yet proven" — the caller skips the group for a tick, which
     * is what it would do for an unproven one anyway.</p>
     *
     * @param minX the group's world AABB minimum corner, already checked for degeneracy by the caller
     * @return true when the group holds a corridor; its three indices are recorded before returning
     */
    public static boolean confirmGroup(ServerLevel level, ManagedShip ship, CarriageDims dims,
                                       int carriageIndex, int groupSize,
                                       double minX, double minY, double minZ) {
        int anchor = PortalCarriageSelection.groupAnchorOf(carriageIndex, groupSize);
        long now = level.getGameTime();
        Long next = NEXT_CONFIRM.get(anchor);
        if (next != null && now < next) return false;
        NEXT_CONFIRM.put(anchor, now + CONFIRM_PERIOD_TICKS);

        PortalCorridorKind kind = PortalCarriageSelection.corridorKindFor(level, anchor);
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, kind);
        // The entry corridor keeps its slot's origin, so the group's minimum corner plus the back pad
        // IS its origin — the same arithmetic PortalCarriageEvents.corridorOriginX does for slot 0.
        double originX = minX + CarriagePlacer.halfPadLen(dims)
            + PortalCorridorSize.originOffsetX(PortalCarriageRole.ENTRY, dims, kind);

        if (!hasCrossingLanterns(ship, layout, originX, minY, minZ)) {
            LOGGER.warn("[DungeonTrain] Portal group {} is claimed by the current selection rate but "
                    + "holds no corridor: no crossing-zone lantern in the entry carriage at ({}, {}, "
                    + "{}). Its blocks were stamped under a different rate — game mode moves it. "
                    + "Refusing to build a swap plane over it.",
                anchor, fmt(originX), fmt(minY), fmt(minZ));
            return false;
        }

        PortalRegistry registry = PortalRegistry.get(level);
        for (int slot = 0; slot < PortalCarriageSelection.PORTAL_GROUP_SPAN && slot < groupSize;
                slot++) {
            registry.noteStamped(anchor + slot, true);
        }
        LOGGER.info("[DungeonTrain] Portal group {} confirmed from its own blocks and recorded — a "
            + "world saved before the stamp record existed.", anchor);
        return true;
    }

    /**
     * True if the corridor's crossing zone still has at least one of its floor lanterns.
     *
     * <p>Block-position rounding is why the read is offset by half a block: the corridor's origin
     * rides the ship's fractional pose, so the cell whose <i>centre</i> is at the local offset is the
     * one that was stamped there.</p>
     */
    private static boolean hasCrossingLanterns(ManagedShip ship, PortalCarriageLayout layout,
                                               double originX, double originY, double originZ) {
        for (int dx = 0; dx < layout.length(); dx++) {
            if (!layout.isCrossingZone(dx)) continue;
            for (int dz = layout.interiorMinZ(); dz <= layout.interiorMaxZ(); dz++) {
                BlockState expected = PortalCarriageBuilder.stateAt(layout, dx, layout.floorY(), dz);
                if (expected == null) continue;
                BlockPos world = BlockPos.containing(
                    originX + dx + 0.5, originY + layout.floorY() + 0.5, originZ + dz + 0.5);
                if (CarriageDeck.blockAt(ship, world).is(expected.getBlock())) return true;
            }
        }
        return false;
    }

    private static String fmt(double value) {
        return String.format("%.2f", value);
    }
}
