package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.PortalExitSites.Site;
import games.brennan.dungeontrain.portal.PortalRoomTiling.Tile;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Comparator;
import java.util.Set;

/**
 * Keeps an endless room's extra corridors standing — the working half of {@link PortalRoomExits},
 * beside {@link PortalRoomTiler}, which does the same job for the room copies themselves.
 *
 * <h2>One corridor per tick, and only where a room already stands</h2>
 * <p>Candidates are drawn from the <b>resident tiles</b> rather than from the window, so a copy is
 * never laid into space that has not been built: its mouth has to open onto a room, and its seal ring
 * has to have a room cross-section to fill. That also bounds the scan — the resident set is capped by
 * {@link PortalRoomTiling#budgetTiles}.</p>
 *
 * <h2>Why a copy is held long after its tile has gone</h2>
 * <p>A corridor is longer than a room ({@link PortalCorridorSize}: 13 against 11 at the default
 * dims), so a copy's blocks reach into the tiles either side of the one it opens into, and those
 * tiles were stamped <i>around</i> it through the corridor mask. Clearing a copy while one of them
 * still stands would leave a corridor-shaped pit with no floor in a room the player can walk straight
 * back into — a hole with loot in it and no way to explain it. {@link PortalExitCopies#nextToRemove}
 * therefore holds a copy until its anchor is {@link PortalRoomTiling#MAX_RADIUS} <i>plus</i>
 * {@link PortalExitSites#tileReach} away, by which point every tile it touches has left the window
 * and the erase lands in space that is already gone.</p>
 *
 * <h2>Why a copy might not appear</h2>
 * <p>The same three reasons a room copy might not, and with the same consequence — the site is simply
 * passed over, silently, and tried again next tick: its chunks are not loaded (checked, never forced,
 * because a forced load here is the shape of the Sable worldgen deadlock), or it would land on
 * another pair's structure.</p>
 */
public final class PortalExitCopyTiler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Copies erased per tick once nobody is inside.
     *
     * <p>Matches {@code PortalRoomTiler}'s tile figure for the same reason: a structure may not be
     * re-stamped until it has drained, and the train reaching {@code TWIN_MAX_DRIFT} is what asks for
     * the re-stamp, so draining has to outpace the train.</p>
     */
    private static final int ERASES_PER_TICK = 4;

    private PortalExitCopyTiler() {}

    /**
     * Advance one structure's extra corridors by at most one stamp or one erase, returning the
     * structure as it now stands — {@code structure} itself, unchanged and identical by reference,
     * when there was nothing to do.
     *
     * <p>The caller uses that identity to decide whether to go on and do tile work in the same tick,
     * so this must not return an equal-but-new record when it did nothing.</p>
     *
     * @param standingIn the tiles players are currently in; empty means nobody is inside, which is
     *                   the signal to drain
     * @param radius     how far the window reaches — the copy's own hold distance is this plus the
     *                   reach
     * @param neighbours every other live structure, so a copy is never stamped onto one
     */
    public static PortalStructure tick(ServerLevel level, CarriageDims dims,
                                       PortalStructure structure, Set<Tile> standingIn, int radius,
                                       Collection<PortalStructure> neighbours, int pairKey) {
        PortalExitCopies standing = structure.exitCopies();

        // A room whose walls or exits setting was changed between one visit and the next can arrive
        // here carrying copies it would no longer lay. Shed them; never lay new ones.
        boolean lays = structure.mode().tiles() && structure.exits().lays();

        if (standingIn.isEmpty() || !lays) {
            if (standing.isEmpty()) return structure;
            return drain(level, dims, structure, standingIn, pairKey);
        }

        Tile centre = standingIn.iterator().next();

        Site next = nextToAdd(level, dims, structure, centre, radius, neighbours, pairKey);
        if (next != null) return stamp(level, dims, structure, next, pairKey);

        // A copy somebody is bound to stays, however far outside the window it has fallen. Stepping
        // onto the train empties the room, which collapses the radius to APPROACH_RADIUS and would
        // otherwise retire the copy they had just that second walked out of — so the way back in
        // would always lead to the original, and the binding could never do its job.
        Site stale = standing.nextToRemove(centre, radius, reachOf(structure, dims),
            site -> !PortalExitBindings.anyBoundTo(pairKey, site.tile()));
        if (stale != null) return erase(level, dims, structure, stale);

        return structure;
    }

    /** Shed copies from the outside in, several a tick — before any tile does, never after. */
    private static PortalStructure drain(ServerLevel level, CarriageDims dims,
                                         PortalStructure structure, Set<Tile> standingIn,
                                         int pairKey) {
        int reach = reachOf(structure, dims);
        PortalStructure current = structure;
        for (int i = 0; i < ERASES_PER_TICK; i++) {
            Site farthest = current.exitCopies().farthestFrom(Tile.BASE);
            // A player standing in a copy's corridor resolves to one of the tiles that corridor
            // reaches into, never to its anchor — so "is anybody in this copy?" is asked of the whole
            // span. Erasing one out from under somebody would drop them to the world floor.
            if (farthest == null || occupied(farthest, standingIn, reach)) break;
            current = erase(level, dims, current, farthest);
        }
        return current;
    }

    /** True when any tile this copy reaches into has somebody standing in it. */
    private static boolean occupied(Site site, Set<Tile> standingIn, int reach) {
        for (Tile tile : standingIn) {
            if (site.chebyshevTo(tile) <= reach) return true;
        }
        return false;
    }

    /**
     * The next copy to lay: the nearest site to {@code centre} that a standing tile owes, is inside
     * {@code radius}, is not up yet, and can be built — or {@code null}.
     *
     * <p><b>Nearest first, explicitly.</b> {@code PortalRoomTiling} fills its window nearest-first,
     * but its resident set is a {@code Set.copyOf} and so has no order to inherit — iterating it and
     * taking the first hit would lay a copy five rooms away before one the player is walking towards.
     * Ties break on coordinates and role so a tick's choice is deterministic rather than dependent on
     * the set's hash order.</p>
     *
     * <p><b>The radius is the fix for a thrash, not a nicety.</b> "Owed by a standing tile" and
     * "inside the window" come apart the moment the window shrinks: a player who steps back onto the
     * train drops the radius to {@link PortalRoomTiling#APPROACH_RADIUS}, while the tiles themselves
     * retire only one per tick, so distant tiles stay resident for a while. Without this check those
     * tiles kept owing copies that {@link PortalExitCopies#nextToRemove} immediately retired again —
     * observed live at one full corridor stamp and erase every tick, 113 of them at a single site in
     * a five-minute session. Adding inside {@code radius} and removing beyond
     * {@code radius + reach} leaves the reach as a hysteresis band between the two rules, which is
     * what stops them arguing.</p>
     */
    private static Site nextToAdd(ServerLevel level, CarriageDims dims, PortalStructure structure,
                                  Tile centre, int radius, Collection<PortalStructure> neighbours,
                                  int pairKey) {
        PortalExitCopies standing = structure.exitCopies();
        long seed = PortalExitSites.seedFor(level.getSeed(), pairKey, structure.roomName());
        Site best = null;
        for (Tile tile : structure.tiling().tiles()) {
            for (Site site : PortalExitSites.owedAt(structure.exits(), tile, seed)) {
                if (standing.has(site)) continue;
                // Never a copy where the pair's REAL exit already stands. A moved exit goes to a site
                // these same rules chose (PortalExitSites#relocatedExitTile), so the rules owe a copy
                // there too — and one laid there would be tracked as a copy and eventually retired,
                // erasing the only way onward.
                if (site.role() == PortalCarriageRole.EXIT
                    && site.tile().equals(structure.exitTile())) {
                    continue;
                }
                if (site.chebyshevTo(centre) > radius) continue;
                if (best != null && nearestTo(centre).compare(site, best) >= 0) continue;
                if (!canStamp(level, dims, structure, site, neighbours)) continue;
                best = site;
            }
        }
        return best;
    }

    /** Nearest first, ties broken on coordinates and role so a tick's choice is deterministic. */
    private static Comparator<Site> nearestTo(Tile centre) {
        return Comparator.comparingInt((Site s) -> s.chebyshevTo(centre))
            .thenComparingInt(s -> s.tile().x())
            .thenComparingInt(s -> s.tile().z())
            .thenComparing(Site::role);
    }

    private static PortalStructure stamp(ServerLevel level, CarriageDims dims,
                                         PortalStructure structure, Site site, int pairKey) {
        PortalCarriageBuilder.stampExitCopy(level, structure, dims, pairKey, site);
        LOGGER.debug("[DungeonTrain] Portal exit copy laid at {} ({})", site.tile(), site.role());
        return structure.withExitCopies(structure.exitCopies().with(site));
    }

    /**
     * Clear one copy back to air.
     *
     * <p>Masked by every corridor that is <b>not</b> this one — the base pair and every other copy —
     * so a plug that happens to abut a neighbour's volume cannot take a bite out of it. Nothing is
     * put back where the copy stood: by the time a copy retires, every tile it reached into has left
     * the window too, so there is no room left for it to leave a hole in.</p>
     */
    private static PortalStructure erase(ServerLevel level, CarriageDims dims,
                                         PortalStructure structure, Site site) {
        PortalStructure shrunk = structure.withExitCopies(structure.exitCopies().without(site));
        BoundingBox box = copyBox(structure, dims, site);
        if (box != null) {
            PortalClear.clearBox(level, box, PortalCarriageBuilder.allCorridorMask(shrunk, dims));
        }
        LOGGER.debug("[DungeonTrain] Portal exit copy retired at {} ({})", site.tile(), site.role());
        return shrunk;
    }

    /** Every block this copy occupies — read off the same mask that protects it while it stands. */
    private static BoundingBox copyBox(PortalStructure structure, CarriageDims dims, Site site) {
        return PortalCorridorMask.forCorridor(
            structure.shadowAt(site.tile()), dims, PortalCarriageBuilder.layoutFor(dims, structure.kind()),
            PortalCarriageBuilder.plugDepth(), site.role()).bounds();
    }

    /** How far, in tiles, a copy's blocks reach past the room it opens into. */
    private static int reachOf(PortalStructure structure, CarriageDims dims) {
        return PortalExitSites.tileReach(
            PortalCorridorSize.corridorLength(dims, structure.kind()), structure.roomLength(),
            PortalCarriageBuilder.plugDepth());
    }

    private static boolean canStamp(ServerLevel level, CarriageDims dims, PortalStructure structure,
                                    Site site, Collection<PortalStructure> neighbours) {
        BoundingBox box = copyBox(structure, dims, site);
        if (box == null) return false;
        if (!PortalRoomTiler.chunksLoaded(level, box)) return false;
        for (PortalStructure other : neighbours) {
            if (other == structure) continue;
            if (box.intersects(PortalCarriageBuilder.footprintOf(level, other, dims))) {
                LOGGER.debug("[DungeonTrain] Portal exit copy at {} skipped — it would land on another pair",
                    site.tile());
                return false;
            }
        }
        return true;
    }
}
