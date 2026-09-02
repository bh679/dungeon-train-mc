package games.brennan.dungeontrain.portal;

import java.util.HashMap;
import java.util.Map;

/**
 * When a portal corridor that cannot swap anybody should open its centre-wall plate instead, so the
 * group is walked through rather than being three carriages of dead end.
 *
 * <p><b>Why a portal needs a way to give up.</b> The crossing is a teleport at the corridor's
 * midpoint ({@code PortalFrames}); the corridor's far end is a dummy door in front of the black
 * plate {@link PortalCentreWall} describes. Every refusal the swap can hand back
 * ({@code PortalSwapDiagnostics.Reason}) leaves that plate closed — a twin whose chunks fell behind
 * the train, a landing with nothing under it, a room that would not fit the world. From inside the
 * corridor all of them look the same: a door onto a wall, and the only way on is to mine it. A
 * severed pair already opens the plate ({@code PortalSever}); this does the same for a pair that is
 * simply not working, without recording anything, so the next re-stamp closes it again and the
 * portal gets another chance.</p>
 *
 * <p><b>Fed by refusals, never by presence.</b> The caller reports one bit per corridor per tick:
 * whether this tick's swap was <i>wanted and refused</i> for a reason that will not clear on its own.
 * A player merely standing in a working corridor never counts, because a refusal is only reachable
 * once the facing rule has asked for a move. That is what keeps this from opening a plate on a
 * portal that works.</p>
 *
 * <p><b>Gap-tolerant.</b> A player at the far door who glances back toward the train gets no move
 * that tick, and a naive "consecutive refusals" count would reset every time they turned their
 * head. A streak here survives a gap of up to {@link #STREAK_GAP_TICKS}; only a longer silence
 * starts it over.</p>
 *
 * <p><b>Re-issued, quietly.</b> Opening the plate is an idempotent block write, and a re-stamp of
 * the group mid-episode would seal it again. So once open, the decision repeats every
 * {@link #REOPEN_PERIOD_TICKS} for as long as refusals keep arriving — but only the first open of
 * an episode is worth a log line.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap. Keyed by carriage index,
 * which is a fixed place along the track; state lives for the server session only.</p>
 */
public final class PortalWalkThrough {

    /** What the caller should do this tick. */
    public enum Decision {
        /** Leave the plate alone. */
        NONE,
        /** Open the plate, and say so — the first open of this episode. */
        OPEN_AND_LOG,
        /** Open the plate again (idempotent), without a line. */
        OPEN_QUIET
    }

    /** Refusals have to keep arriving for this long before the plate opens: two seconds. */
    public static final int OPEN_AFTER_TICKS = 40;

    /** A silence longer than this ends the streak; a shorter one is a glance back toward the train. */
    public static final int STREAK_GAP_TICKS = 20;

    /** How often an open plate is re-asserted while the refusals continue. */
    public static final int REOPEN_PERIOD_TICKS = 20;

    /** One corridor's episode. */
    private static final class Streak {
        long start;
        long lastRefused;
        /** Game time of the last open issued, or {@code null} while the plate is still closed. */
        Long lastOpened;

        Streak(long now) {
            start = now;
            lastRefused = now;
        }
    }

    private static final Map<Integer, Streak> STREAKS = new HashMap<>();

    private PortalWalkThrough() {}

    /**
     * Report this tick for one corridor and learn whether to open its plate.
     *
     * @param carriageIndex the corridor's index along the track
     * @param now           the level's game time
     * @param refused       {@code true} if a swap was wanted and refused this tick for a reason that
     *                      does not clear on its own
     */
    public static synchronized Decision noteTick(int carriageIndex, long now, boolean refused) {
        Streak streak = STREAKS.get(carriageIndex);
        if (!refused) {
            if (streak != null && now - streak.lastRefused > STREAK_GAP_TICKS) {
                STREAKS.remove(carriageIndex);
            }
            return Decision.NONE;
        }

        if (streak == null || now - streak.lastRefused > STREAK_GAP_TICKS) {
            streak = new Streak(now);
            STREAKS.put(carriageIndex, streak);
        }
        streak.lastRefused = now;

        if (now - streak.start < OPEN_AFTER_TICKS) return Decision.NONE;

        if (streak.lastOpened == null) {
            streak.lastOpened = now;
            return Decision.OPEN_AND_LOG;
        }
        if (now - streak.lastOpened >= REOPEN_PERIOD_TICKS) {
            streak.lastOpened = now;
            return Decision.OPEN_QUIET;
        }
        return Decision.NONE;
    }

    /** True while this corridor's plate has been opened by an episode that has not ended. */
    public static synchronized boolean isOpen(int carriageIndex) {
        Streak streak = STREAKS.get(carriageIndex);
        return streak != null && streak.lastOpened != null;
    }

    /** Game time this corridor's episode began, for {@code /dungeontrain portal diagnose}. */
    public static synchronized Long episodeStart(int carriageIndex) {
        Streak streak = STREAKS.get(carriageIndex);
        return streak == null ? null : streak.start;
    }

    /** End a corridor's episode — nobody is near it any more. The next one logs afresh. */
    public static synchronized void forget(int carriageIndex) {
        STREAKS.remove(carriageIndex);
    }

    /** Drop everything — called when the server stops. */
    public static synchronized void clear() {
        STREAKS.clear();
    }
}
