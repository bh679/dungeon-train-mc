package games.brennan.dungeontrain.portal;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carriage groups a debug tool has asked to hold a portal regardless of the draw — the transient
 * override behind {@code /dungeontrain portal test}.
 *
 * <p><b>Why an override rather than the rate.</b> The obvious way to get a dimensional carriage in
 * front of you is {@code /dungeontrain portal carriage 1}, and that is exactly what a test button
 * must not do: the rate is world state, it persists, and setting it calls
 * {@link games.brennan.dungeontrain.cheat.PortalTuningIntegrity#markTuned} — the
 * {@code free_play.cause.portal_rate} trip. Forcing one group instead writes nothing down and
 * leaves the world's own rate saying whatever it said before.</p>
 *
 * <p><b>Session-only, deliberately.</b> Nothing here is persisted. Carriages already stamped keep
 * their portal verdict across a restart — it is recorded when the blocks are laid
 * ({@link PortalRegistry#noteStamped}) and read back through {@link PortalStampRecord}, which is the
 * invariant {@link PortalCarriageSelection#rateFor} leans on — but a group re-stamped after the
 * server has restarted comes back an ordinary one. That is the right lifetime for a test spawn:
 * nothing a debug button does should outlive the session that pressed it.</p>
 *
 * <p>Read by the level-aware entries of {@link PortalCarriageSelection}, ahead of the rate and the
 * Diff-Level gate, so a forced group is a portal even in a world with portals switched off. Cleared
 * with the rest of the portal statics in
 * {@code PortalCarriageEvents.onServerStopped}.</p>
 */
public final class PortalForcedGroups {

    /** Group ordinals — {@code floorDiv(carriageIndex, groupSize)}, not carriage indices. */
    private static final Set<Long> FORCED = ConcurrentHashMap.newKeySet();

    private PortalForcedGroups() {}

    /** Make {@code groupOrdinal} hold a portal for the rest of this session. */
    public static void force(long groupOrdinal) {
        FORCED.add(groupOrdinal);
    }

    public static boolean isForced(long groupOrdinal) {
        return FORCED.contains(groupOrdinal);
    }

    /** True if any group is forced — the cheap test the hot path asks first. */
    public static boolean isEmpty() {
        return FORCED.isEmpty();
    }

    public static void clear() {
        FORCED.clear();
    }
}
