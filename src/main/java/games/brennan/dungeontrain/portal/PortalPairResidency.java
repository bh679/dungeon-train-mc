package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyard;
import games.brennan.dungeontrain.ship.Shipyards;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a portal pair's carriage group loaded for as long as anybody is inside its room.
 *
 * <h2>What goes wrong without it</h2>
 * <p>Every portal swap — the base twin and every extra corridor an endless room scatters through its
 * tiles — is driven from {@code PortalCarriageEvents}' walk over live carriage groups. A player who
 * walks eight rooms out is nowhere near the train, so nothing holds the group in the simulation
 * bubble, and Sable culls its sub-level to holding. The walk then skips the group, {@code
 * handlePortalCarriage} never runs, and <b>every corridor in the room goes inert at once</b>: the
 * one they came in through, and each copy that was supposed to be a second way back. The room is
 * sealed and repeats forever, so there is nothing else to walk to.</p>
 *
 * <p>A force-load ticket on the group is the whole fix for that case. It is preventive — see
 * {@link Shipyard#forceLoad} — so it has to be taken while the group is still live, which is why
 * this runs off "is anybody in the room" every tick rather than off the moment somebody walks in.
 * Once the group is already held, {@link PortalCarriageRevival} is what brings it back.</p>
 *
 * <h2>Held only while occupied</h2>
 * <p>{@link #syncTo} takes and releases in one pass, so the ticket's lifetime is exactly the room's
 * occupancy: step back onto the train and the group can be culled again like any other. That
 * matters, because a portal every few carriages means a train can accumulate a lot of rooms, and a
 * ticket that outlived its visit would pin a sub-level — and its backing chunks — for the session.</p>
 *
 * <p><b>A ticket left live at save is swept, not leaked.</b> The one way to stop the server holding
 * one is to quit while standing in a room. Sable persists tickets to disk, but every server start
 * rebuilds the train through {@code TrainAssembler}, whose wipe calls
 * {@link Shipyard#releaseAllForceLoads()} before anything re-fills — so the straggler is released
 * on the next load rather than resurrecting an orphan carriage. This class therefore only has to
 * forget its own bookkeeping when the server stops.</p>
 */
public final class PortalPairResidency {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Pair key → the carriage group it lives on, as last seen by the tick walk.
     *
     * <p>Kept rather than looked up because the lookup is exactly what stops working: {@code
     * Trains.knownGroups} is keyed by train id, and reaching the train id means finding a <i>loaded</i>
     * carriage of that train. A pair whose group has been culled may have no loaded carriage anywhere
     * near it. The handle recorded here survives the cull — a stale wrapper still answers
     * {@link ManagedShip#subLevelId()}, which is all {@link PortalCarriageRevival} needs to find it in
     * holding.</p>
     *
     * <p>Both corridors of a pair sit in one group, so the two carriages write the same handle and the
     * put is idempotent.</p>
     */
    private static final Map<Integer, ManagedShip> GROUPS = new ConcurrentHashMap<>();

    /** Pair keys this class currently holds a force-load ticket for. */
    private static final Set<Integer> TICKETED = ConcurrentHashMap.newKeySet();

    private PortalPairResidency() {}

    /**
     * Note which carriage group this pair rides on. Called from the tick walk, which is the only
     * place that has both facts in hand.
     */
    public static void note(int pairKey, ManagedShip ship) {
        if (ship == null) return;
        GROUPS.put(pairKey, ship);
    }

    /**
     * This pair's carriage group, or {@code null} if no walk has ever reached it.
     *
     * <p>May be a stale handle — one whose sub-level has been culled. Callers that intend to read a
     * live pose must gate on {@link ManagedShip#isResident()}, exactly as the tick walk does.</p>
     */
    public static ManagedShip groupFor(int pairKey) {
        return GROUPS.get(pairKey);
    }

    /**
     * Hold exactly the pairs in {@code occupied} and release every other ticket.
     *
     * <p>One pass rather than a hold call and a release call, so the two can never disagree about
     * which pairs are occupied this tick.</p>
     */
    public static void syncTo(ServerLevel level, Set<Integer> occupied) {
        if (level == null) return;
        if (occupied.isEmpty() && TICKETED.isEmpty()) return;

        Shipyard shipyard = Shipyards.of(level);
        for (Integer pairKey : occupied) {
            if (TICKETED.contains(pairKey)) continue;
            ManagedShip ship = GROUPS.get(pairKey);
            // A culled group cannot be force-loaded back — the ticket only keeps a live sub-level
            // live. PortalCarriageRevival is the path for one that has already gone, and it will be
            // ticketed here on the tick after it surfaces.
            if (ship == null || !ship.isResident()) continue;
            // PORTAL_ROOM, never the default: the appender's trailing window reconciles every tick
            // and releases the ticket on every sub-level outside it. Sharing its type meant the
            // window revoked this hold and Sable culled the group with a player standing in its
            // room — the exact failure this class exists to prevent. See Shipyard.Hold.
            shipyard.forceLoad(ship, Shipyard.Hold.PORTAL_ROOM);
            TICKETED.add(pairKey);
            LOGGER.debug("[DungeonTrain] Portal pair {} force-loaded — somebody is in its room", pairKey);
        }

        for (Iterator<Integer> it = TICKETED.iterator(); it.hasNext(); ) {
            Integer pairKey = it.next();
            if (occupied.contains(pairKey)) continue;
            it.remove();
            ManagedShip ship = GROUPS.get(pairKey);
            if (ship != null) shipyard.releaseForceLoad(ship, Shipyard.Hold.PORTAL_ROOM);
            LOGGER.debug("[DungeonTrain] Portal pair {} released — its room is empty", pairKey);
        }
    }

    /** True if a ticket is currently held for this pair. For tests and {@code portal diagnose}. */
    public static boolean holds(int pairKey) {
        return TICKETED.contains(pairKey);
    }

    /** How many tickets are outstanding — for a diagnostic that wants to see a leak. */
    public static int held() {
        return TICKETED.size();
    }

    /**
     * Forget every handle and every ticket.
     *
     * <p>Called when the server stops, alongside the rest of {@code PortalCarriageEvents}' static
     * state: a pair key means a different place in the next world a single-player client opens, so a
     * surviving handle would name somebody else's carriage. The tickets themselves are released by
     * the bootstrap sweep described in this class's javadoc.</p>
     */
    public static void clear() {
        GROUPS.clear();
        TICKETED.clear();
    }
}
