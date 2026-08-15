package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyard;
import games.brennan.dungeontrain.ship.Shipyards;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bringing a portal pair's carriage group back from Sable holding, so a corridor in the room always
 * has a train to lead to.
 *
 * <p>{@link PortalPairResidency} is the prevention — a group with somebody in its room is held
 * loaded and never gets here. This is what happens when that came too late: the player was already
 * deep in the room when the group was culled, or they walked in during the tick it went. Then every
 * corridor in the room is inert, and since an endless room repeats forever there is nowhere else to
 * walk. The pair has to be revived rather than waited on.</p>
 *
 * <h2>Revive, then swap off the live pose</h2>
 * <p>A culled group's handle still answers {@code worldAABB()} with its last pose, and a swap run off
 * that would put the player where the carriage <i>used to be</i> — open track, at speed, with no
 * sub-level under them. So nothing is teleported here. The reload surfaces the group in
 * {@code findAll()} within a few ticks; the ordinary walk in {@code PortalCarriageEvents} then reaches
 * it and the swap fires from the real pose, wherever the train has since travelled to. The player
 * stands in the corridor for a moment and then steps onto the train.</p>
 *
 * <h2>Issued once per episode</h2>
 * <p>{@link Shipyard#reloadFromHolding} is not a poll. A held group stays held for the whole
 * surfacing window, so calling it every tick only re-triggers Sable's benign "wasn't present in the
 * holding chunk" ERROR while the first reload is still working. The throttle here is the same shape
 * as {@code TrainCarriageAppender.claimReloadIssue}: one issue per {@code (pairKey, subLevelId)}, and
 * a new sub-level id — or the group coming back — re-arms it.</p>
 */
public final class PortalCarriageRevival {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Pair key → the sub-level id a reload has already been issued for. */
    private static final Map<Integer, UUID> RELOAD_ISSUED = new ConcurrentHashMap<>();

    /** What a pair's carriage group is doing, from the point of view of somebody wanting to leave. */
    public enum Status {

        /** Live and readable — the ordinary swap can run off its pose. */
        LIVE,

        /** Culled but recoverable: a reload has been issued and it is on its way back. */
        REVIVING,

        /**
         * Not resident and not in holding.
         *
         * <p>Either no walk ever reached this pair (so there is no handle to work from), or the
         * sub-level was culled before it was ever serialized and has no pointer to snatch — the
         * "ghost" case {@link ManagedShip#hasSerializationPointer()} describes. Nothing here can
         * bring that back; it is reported rather than papered over.</p>
         */
        GONE
    }

    private PortalCarriageRevival() {}

    /**
     * Make sure this pair's group is live, reviving it if it has been culled to holding.
     *
     * @param ship this pair's group handle, as recorded by {@link PortalPairResidency#groupFor} —
     *             possibly stale, possibly {@code null}
     */
    public static Status ensureLive(ServerLevel level, int pairKey, ManagedShip ship) {
        if (level == null || ship == null) return Status.GONE;

        if (ship.isResident()) {
            // Re-arm: the next time this pair is culled is a new episode, and it must be allowed its
            // own reload. Done on the way past rather than swept, for the reason PortalExitBindings
            // drops an expired trail on read — this is when it is cheapest to notice.
            RELOAD_ISSUED.remove(pairKey);
            return Status.LIVE;
        }

        UUID subLevelId = ship.subLevelId();
        Shipyard shipyard = Shipyards.of(level);
        if (!shipyard.isHeld(subLevelId)) return Status.GONE;

        if (claimReload(pairKey, subLevelId)) {
            boolean reloaded = shipyard.reloadFromHolding(subLevelId);
            LOGGER.info("[DungeonTrain] Portal pair {} is culled and somebody is in its room — "
                    + "{} its carriage group (subLevelId={}) from holding; the way out reopens once it surfaces",
                pairKey, reloaded ? "reloaded" : "could not reload", subLevelId);
        }
        return Status.REVIVING;
    }

    /**
     * Claim the single reload issue for one held episode. True only on the first call per
     * {@code (pairKey, subLevelId)}.
     *
     * <p>Package-visible and free of any world so the throttle rule can be unit-tested on its own —
     * the same split {@link PortalExitBindings#followerFor} makes for the same reason.</p>
     */
    static boolean claimReload(int pairKey, UUID subLevelId) {
        if (subLevelId == null) return false;
        return !subLevelId.equals(RELOAD_ISSUED.put(pairKey, subLevelId));
    }

    /** Forget one pair's throttle — for tests, and for a pair whose structure has gone. */
    public static void forget(int pairKey) {
        RELOAD_ISSUED.remove(pairKey);
    }

    /** Forget every throttle. Called when the server stops, with the rest of the portal state. */
    public static void clear() {
        RELOAD_ISSUED.clear();
    }
}
