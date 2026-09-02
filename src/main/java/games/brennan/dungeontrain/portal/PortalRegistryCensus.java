package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * How much of the carriage registry is still alive, counted out loud.
 *
 * <p><b>The question this exists to answer.</b> {@code Trains.SPAWNED_GROUPS} is grow-only within a
 * session: {@code TrainCarriageAppender.cleanupGhostAnchors} drops anchors <i>past the visible
 * edge</i>, so a group culled while the train rolls over it — interior to the span the walk covers —
 * is never a candidate and stays registered for as long as the world is open. A field log showed 46
 * groups being refused a swap, ~30 of them continuously for a whole session, and left the one
 * question that decides what to do about them unanswerable: are those entries <b>held</b> (culled to
 * Sable's holding storage, recoverable, and deleting them is the historic duplicate-on-respawn race)
 * or <b>gone</b> (nothing can bring them back, so a sweep could safely drop them)? The refusal lines
 * cannot tell those apart. This can.</p>
 *
 * <p><b>Free when it is quiet, which is why it is always on.</b> {@link #due} is asked <i>once per
 * tick, before</i> the walk, and its answer is carried into the loop as a flag — so on the ticks
 * where no census is due the whole mechanism costs one comparison, and not a single
 * {@code isHeld} lookup happens. Same rationale as {@link PortalSwapDiagnostics}: a diagnostic that
 * costs something gets switched off, and a diagnostic that is off does not answer the next report.</p>
 *
 * <p><b>Strictly observational.</b> Nothing here mutates the registry, deletes a sub-level, or
 * reloads one. In particular it must never reach {@link PortalCarriageRevival#ensureLive}, which
 * <i>reloads from holding</i> as a side effect — a census that revived what it counted would be
 * measuring itself.</p>
 */
public final class PortalRegistryCensus {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Ticks between censuses. 600 ≈ 30s: slow enough that a long session stays readable, often
     * enough that the shape of the growth is visible across a few minutes of riding rather than
     * needing an hour to show up.
     */
    static final int PERIOD_TICKS = 600;

    /**
     * Game time the last census was taken at, or {@link Long#MIN_VALUE} for "never".
     *
     * <p>One window for the level rather than one per train: trains are few, the counts are read
     * together, and a single window keeps {@link #due} to the one call per tick that makes the
     * throttle free.</p>
     */
    private static long lastCensusAt = Long.MIN_VALUE;

    private PortalRegistryCensus() {}

    /**
     * Whether this tick should take a census, marking the window as taken.
     *
     * <p><b>Asking marks it asked</b>, so this must be called exactly once per tick and its answer
     * reused — the same contract {@link PortalSwapDiagnostics#due} carries, and for the same reason:
     * a second caller in the same tick would consume the window and the first would report nothing.</p>
     */
    public static boolean due(ServerLevel level) {
        if (level == null) return false;
        return dueAt(level.getGameTime());
    }

    /**
     * {@link #due} without the Minecraft type, so the window rule is unit-testable.
     *
     * <p>A game time that has gone <i>backwards</i> counts as due and re-anchors the window: the
     * clock only moves back when a different world has been opened, and the alternative is a census
     * that stays silent until the new world catches up to the old one's tick count.</p>
     */
    static boolean dueAt(long gameTime) {
        if (lastCensusAt != Long.MIN_VALUE
                && gameTime >= lastCensusAt
                && gameTime - lastCensusAt < PERIOD_TICKS) {
            return false;
        }
        lastCensusAt = gameTime;
        return true;
    }

    /**
     * Write down one train's tally.
     *
     * <p>{@code gone} is the number the follow-up decision hangs on: it is what a sweep restricted
     * to truly-unrecoverable entries — the rule {@code cleanupGhostAnchors} already applies at its
     * edges — would actually be able to remove. A census that reports {@code gone=0} with a large
     * {@code held} says that such a sweep would be a no-op and the entries are recoverable by
     * design; a large {@code gone} says the opposite.</p>
     */
    public static void report(UUID trainId, int groups, int resident, int held, int gone,
                              int minAnchor, int maxAnchor) {
        LOGGER.info("[DungeonTrain] {}", format(trainId, groups, resident, held, gone, minAnchor, maxAnchor));
    }

    /** The census line's text, split out so its shape is unit-testable without a logger. */
    static String format(UUID trainId, int groups, int resident, int held, int gone,
                         int minAnchor, int maxAnchor) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("Registry census trainId=").append(trainId)
            .append(" groups=").append(groups)
            .append(" resident=").append(resident)
            .append(" held=").append(held)
            .append(" gone=").append(gone);
        // An empty registry has no anchors to bound, and printing the sentinel extremes would read
        // as a span covering the whole int range.
        if (groups > 0) {
            sb.append(" anchors=[").append(minAnchor).append(',').append(maxAnchor).append(']');
        }
        return sb.toString();
    }

    /**
     * Forget the window.
     *
     * <p>Called alongside the other static state {@code PortalCarriageEvents.onServerStopped} drops.
     * A surviving anchor would silence the first half-minute of the next world a single-player
     * client opens — the window somebody debugging registry growth would be watching.</p>
     */
    public static void clear() {
        lastCensusAt = Long.MIN_VALUE;
    }
}
