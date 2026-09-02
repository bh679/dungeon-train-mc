package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Why a portal swap did not happen, said out loud.
 *
 * <p><b>The problem this exists for.</b> A swap can be refused by half a dozen different conditions,
 * and most of them used to be a bare {@code continue}. From inside the game every one of them looks
 * identical — you walk past the midpoint and nothing happens — and from the log they were
 * indistinguishable from a corridor that was never a portal, a group that was culled, or a player who
 * simply had not reached the line yet. "Sometimes the portal doesn't work" was therefore unanswerable
 * after the fact, which is the whole reason this class is here: a refusal that names itself turns a
 * report into a diagnosis.</p>
 *
 * <p><b>Throttled per subject and reason.</b> Every one of these conditions re-qualifies on the
 * <i>next tick</i> — a player standing past the midpoint of a severed corridor asks and is refused
 * twenty times a second — so an ungated log would bury everything else in the file. The same
 * rationale, and roughly the same period, as the skip and landing warnings that
 * {@code PortalCarriageEvents} already throttles. Keyed on subject <b>and</b> reason so a pair failing
 * two different ways still says both.</p>
 *
 * <p><b>Not a rate limiter on the failure.</b> Nothing here changes what the portal does; every
 * refusal still refuses. This only decides how often it is worth writing down.</p>
 *
 * <p><b>Free when it is quiet, which is why it is always on.</b> The throttle is tested through
 * {@link #due} <i>before</i> any caller builds a message, so a condition already reported inside its
 * window costs two map lookups and a comparison — no formatting, no concatenation, not even a call to
 * ask the subject its name. That is deliberate: diagnostics that cost something get switched off, and
 * diagnostics that are off do not answer the next report. The paths that reach here are anomalous to
 * begin with — a swap the rule wanted and did not get — so even the unthrottled case is a handful of
 * lines, not a per-tick tax.</p>
 */
public final class PortalSwapDiagnostics {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Ticks between repeats of one subject's one reason. Five seconds: often enough that a test
     * session shows the shape of an episode, quiet enough that a long one stays readable.
     */
    private static final int PERIOD_TICKS = 100;

    /**
     * Reason → subject identity → game time it was last logged.
     *
     * <p>Nested rather than keyed on a composite so that looking a throttle up costs no allocation:
     * the outer key is an enum and the inner one is the subject's own identity object. See
     * {@link #due}.</p>
     */
    private static final Map<Reason, Map<Object, Long>> LAST_LOGGED = new EnumMap<>(Reason.class);

    /**
     * Why a swap was refused.
     *
     * <p>Ordered roughly as the checks run, from "the player is not eligible" through "the pair is
     * not in a state to carry one" to "the destination is not fit to land in".</p>
     */
    public enum Reason {

        /** Riding something. The train carries its passengers through; the portal leaves them alone. */
        PASSENGER(true, "player is riding an entity — passengers are carried through, never swapped"),

        /** Swapped moments ago and still inside the settling window. Ordinary, and self-clearing. */
        COOLDOWN(true, "player swapped within the last second — waiting for the client to acknowledge it"),

        /** The shell was broken past the midpoint. Permanent until {@code portal severed clear}. */
        SEVERED("this pair's corridor shell was broken open — the way IN is closed for good"),

        /**
         * The destination corridor's chunks are not present.
         *
         * <p>The one to look for first when a portal "sometimes" fails: it means the twin is stamped
         * somewhere the client no longer has, which is a twin that did not follow its carriage.</p>
         */
        TWIN_NOT_LOADED("the destination corridor's chunks are not loaded — the twin has been left behind"),

        /** Chunks present but nothing solid under the landing, so the twin is not actually stamped there. */
        NO_LANDING("nothing to stand on at the destination — the corridor is not stamped there"),

        /** No twin at all: the pocket structure could not be placed for this pair. */
        NO_TWIN_STRUCTURE("this pair has no twin structure — there is no room for one under this world"),

        /** The group's sub-level has been culled, so its last pose cannot be trusted. */
        GROUP_NOT_RESIDENT("the carriage group's sub-level is culled — its pose is stale"),

        /** Sable handed back a zero box for a sub-level that has not ticked yet. */
        DEGENERATE_AABB("the carriage group's bounding box is degenerate — it has not ticked yet"),

        /**
         * The group is on its way back from holding and the corridor is waiting for it.
         *
         * <p>Benign: somebody is in the room, the pair was culled, and {@link PortalCarriageRevival}
         * has asked for it back. It lasts the few ticks the sub-level takes to surface, after which
         * the ordinary walk picks the pair up and the swap fires off the real pose.</p>
         */
        GROUP_REVIVING(true, "the carriage group is being brought back from holding — the way out reopens when it surfaces"),

        /**
         * Somebody is in a pair's room and no walk reached its group at all.
         *
         * <p>The one that used to be silent, and the reason a copy could be a dead corridor: with the
         * group absent from the tick walk, neither the base twin nor any copy of it does anything, and
         * an endless room has nowhere else to walk to. Reaching this after
         * {@link PortalCarriageRevival} has had its say means the group is neither resident nor in
         * holding — nothing can bring it back.</p>
         */
        PAIR_NOT_WALKED("nobody's walk reached this pair's carriage group, and it is not in holding — its corridors have nothing to lead to"),

        /**
         * The current selection rate claims this group, but its carriages hold no corridor.
         *
         * <p>The disagreement {@link PortalStampRecord} exists to refuse. Reached when the rate that
         * stamped these carriages is not the rate being drawn now — game mode moves it, and so does
         * {@code /dungeontrain portal carriage}. Refusing is the whole point: built on, the swap
         * plane would cover an ordinary carriage and teleport whoever walked down it into a pocket
         * room.</p>
         *
         * <p>Expected exactly once per group on a world saved before the stamp record existed, if
         * that group's blocks could not be read yet. Repeating means a genuine disagreement.</p>
         */
        NOT_STAMPED("the selection rate claims this group but no corridor is stamped in it — its blocks were laid under a different rate");

        /**
         * True for a state that is ordinary rather than wrong — it stops a swap, but nothing about it
         * needs fixing. Logged at INFO; everything else at WARN.
         */
        private final boolean benign;

        private final String explanation;

        Reason(String explanation) {
            this(false, explanation);
        }

        Reason(boolean benign, String explanation) {
            this.benign = benign;
            this.explanation = explanation;
        }

        /** Plain-language reason, for the log and for {@code /dungeontrain portal diagnose}. */
        public String explanation() {
            return explanation;
        }
    }

    private PortalSwapDiagnostics() {}

    /**
     * Whether this subject's refusal for this reason is due to be written down.
     *
     * <p><b>Split from {@link #refused} so that costing nothing is structural rather than a claim.</b>
     * Every caller wraps its message-building in this test, so on the overwhelmingly common path — a
     * condition that has already been reported inside the throttle window — the work done is two map
     * lookups and a long comparison. No message is built, no coordinates are formatted, and the
     * subject's own name is not even asked for. Rolling the two together, with the detail passed as an
     * argument, would have every caller build a string on every tick for the throttle to discard,
     * which is the shape of cost that makes diagnostics worth turning off. This one is not.</p>
     *
     * <p>Keyed on the subject's <i>identity</i> — a {@link java.util.UUID} for a player, the index for
     * a carriage — rather than on its printable name, so nothing is concatenated to look the throttle
     * up either.</p>
     *
     * <p><b>Asking marks it asked</b>, so this must be the <i>last</i> condition a caller tests. A
     * caller that asks and then decides it had nothing to say has spent a window on a tick that logged
     * nothing, and a real occurrence a moment later would be silently swallowed — which is the one
     * failure a diagnostic cannot afford. Where a caller has its own "is this worth reporting" test,
     * that test goes first and this one goes after the {@code &&}.</p>
     */
    public static boolean due(ServerLevel level, Reason reason, Object subject) {
        if (level == null) return false;

        Map<Object, Long> perSubject = LAST_LOGGED.computeIfAbsent(reason, r -> new HashMap<>());
        long now = level.getGameTime();
        Long last = perSubject.get(subject);
        if (last != null && now - last < PERIOD_TICKS) return false;
        perSubject.put(subject, now);
        return true;
    }

    /**
     * Write down a refused swap. Call only inside a {@link #due} test, which is what bounds how often
     * this happens.
     *
     * @param subject who the refusal is about, as it should read in the log
     * @param detail  the specifics worth reading: coordinates, origins, names. Free text
     */
    public static void refused(Reason reason, String subject, String detail) {
        // Benign reasons are ordinary game states that happen to stop a swap — a player in a boat, a
        // player who swapped a moment ago — and logging those at WARN would train the reader to
        // ignore the level that the real failures use.
        if (reason.benign) {
            LOGGER.info("[DungeonTrain] Portal swap skipped [{}] for {}: {} — {}",
                reason.name(), subject, reason.explanation(), detail);
            return;
        }
        LOGGER.warn("[DungeonTrain] Portal swap refused [{}] for {}: {} — {}",
            reason.name(), subject, reason.explanation(), detail);
    }

    /**
     * Forget every throttle.
     *
     * <p>Called when the server stops, alongside the other static state
     * {@code PortalCarriageEvents.onServerStopped} drops. A surviving entry would silence the first
     * few seconds of the next world a single-player client opens — which is exactly the window
     * somebody debugging a portal would be watching. It also keeps the map from holding a UUID for
     * every player who has ever been refused anything.</p>
     */
    public static void clear() {
        LAST_LOGGED.clear();
    }
}
