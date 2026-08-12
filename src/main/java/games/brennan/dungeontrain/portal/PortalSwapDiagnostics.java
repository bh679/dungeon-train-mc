package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

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
 */
public final class PortalSwapDiagnostics {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Ticks between repeats of one subject's one reason. Five seconds: often enough that a test
     * session shows the shape of an episode, quiet enough that a long one stays readable.
     */
    private static final int PERIOD_TICKS = 100;

    /** {@code subject + "/" + reason} → game time it was last logged. */
    private static final Map<String, Long> LAST_LOGGED = new HashMap<>();

    /**
     * Why a swap was refused.
     *
     * <p>Ordered roughly as the checks run, from "the player is not eligible" through "the pair is
     * not in a state to carry one" to "the destination is not fit to land in".</p>
     */
    public enum Reason {

        /** Riding something. The train carries its passengers through; the portal leaves them alone. */
        PASSENGER("player is riding an entity — passengers are carried through, never swapped"),

        /** Swapped moments ago and still inside the settling window. Ordinary, and self-clearing. */
        COOLDOWN("player swapped within the last second — waiting for the client to acknowledge it"),

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

        /**
         * An exit corridor reached before its pair's entry ever placed the structure.
         *
         * <p>Walking a train backwards into an exit corridor before anyone has been within approach
         * range of the entry two slots behind it. The exit waits rather than placing a structure on
         * its own coordinates — see the note in {@code PortalCarriageEvents.handlePortalCarriage}.</p>
         */
        EXIT_WITHOUT_STRUCTURE("exit corridor reached before its entry placed the pair's room — walk toward the entry"),

        /** The group's sub-level has been culled, so its last pose cannot be trusted. */
        GROUP_NOT_RESIDENT("the carriage group's sub-level is culled — its pose is stale"),

        /** Sable handed back a zero box for a sub-level that has not ticked yet. */
        DEGENERATE_AABB("the carriage group's bounding box is degenerate — it has not ticked yet");

        private final String explanation;

        Reason(String explanation) {
            this.explanation = explanation;
        }

        /** Plain-language reason, for the log and for {@code /dungeontrain portal diagnose}. */
        public String explanation() {
            return explanation;
        }
    }

    private PortalSwapDiagnostics() {}

    /**
     * Note a refused swap, at most once per {@link #PERIOD_TICKS} for this subject and reason.
     *
     * @param subject who or what the refusal is about — a player name, or a carriage/group index.
     *                Only ever a throttle key and a log field
     * @param detail  the specifics worth reading: coordinates, origins, names. Free text
     */
    public static void refused(ServerLevel level, String subject, Reason reason, String detail) {
        if (level == null) return;

        String key = subject + "/" + reason.name();
        long now = level.getGameTime();
        Long last = LAST_LOGGED.get(key);
        if (last != null && now - last < PERIOD_TICKS) return;
        LAST_LOGGED.put(key, now);

        LOGGER.warn("[DungeonTrain] Portal swap refused [{}] for {}: {} — {}",
            reason.name(), subject, reason.explanation(), detail);
    }

    /**
     * Forget every throttle.
     *
     * <p>Called when the server stops, alongside the other static state
     * {@code PortalCarriageEvents.onServerStopped} drops. A surviving entry would silence the first
     * few seconds of the next world a single-player client opens — which is exactly the window
     * somebody debugging a portal would be watching.</p>
     */
    public static void clear() {
        LAST_LOGGED.clear();
    }
}
