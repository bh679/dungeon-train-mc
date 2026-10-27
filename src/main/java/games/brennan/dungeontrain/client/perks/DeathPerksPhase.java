package games.brennan.dungeontrain.client.perks;

import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.Supplier;

/**
 * Where the perks menu stands when it opens on death, ahead of the death recap. A life that put
 * points into perks opens on {@link Phase#POINTS}: the sky with those points lit, framed as a death
 * page. Moving on plays {@link Phase#DRAIN}, the rings emptying while the death framing gives way to
 * the menu, then {@link Phase#UNLOCK}: the menu with unlocking only, showing the tree as the next life
 * starts it. A life with no points opens straight on {@link Phase#UNLOCK}.
 *
 * <p>The points page never moves on by itself: only Next Screen, Enter or Esc leave it, and not in
 * its first {@link #READY_MS} ms, so a click or key already on its way at death cannot skip it.</p>
 *
 * <p>The points shown are the ones held at death, frozen when the screen opens; the unlock phase reads
 * the live state, so unlocks made there show at once.</p>
 */
@OnlyIn(Dist.CLIENT)
final class DeathPerksPhase {

    enum Phase { POINTS, DRAIN, UNLOCK }

    /** The whole drain, ms: the death framing fades, the rings empty, the menu fades in. */
    static final long DRAIN_MS = 1400;
    private static final long FRAME_OUT_MS = 300;
    private static final long RINGS_FROM_MS = 200, RINGS_TO_MS = 1000;
    private static final long DIM_LIFTS_MS = 900;
    private static final long MENU_IN_FROM_MS = 1000;
    /** The points page ignores Next Screen this long after it opens. */
    static final long READY_MS = 1000;

    private final PerkMenuSnapshot carried;
    private final Supplier<Screen> next;
    private final long openedAt;
    private Phase phase;
    private long drainAt = -1;

    DeathPerksPhase(PerkMenuSnapshot atDeath, Supplier<Screen> next, long openedAt) {
        this.carried = atDeath;
        this.next = next;
        this.openedAt = openedAt;
        this.phase = atDeath.ranksBought() > 0 ? Phase.POINTS : Phase.UNLOCK;
    }

    /** The phase now; a drain that has run its course becomes the unlock phase. */
    Phase phase(long now) {
        if (phase == Phase.DRAIN && now - drainAt >= DRAIN_MS) phase = Phase.UNLOCK;
        return phase;
    }

    /** The points held at death, as the screen opened. */
    PerkMenuSnapshot carried() {
        return carried;
    }

    /** Leave the points page: start the drain. A second call during the drain skips to its end. */
    void advance(long now) {
        if (phase == Phase.POINTS && now - openedAt < READY_MS) return;
        if (phase == Phase.POINTS) {
            phase = Phase.DRAIN;
            drainAt = now;
        } else if (phase == Phase.DRAIN) {
            phase = Phase.UNLOCK;
        }
    }

    /** What the sky draws this frame. */
    PerkMenuSnapshot view(PerkMenuSnapshot live, long now) {
        return switch (phase(now)) {
            case POINTS -> carried;
            case DRAIN -> carried.drained(ringsLeft(now));
            case UNLOCK -> live.withoutActive();
        };
    }

    /** Share of each ring still filled, 1 down to 0. */
    double ringsLeft(long now) {
        return switch (phase(now)) {
            case POINTS -> 1;
            case DRAIN -> 1 - ramp(now - drainAt, RINGS_FROM_MS, RINGS_TO_MS);
            case UNLOCK -> 0;
        };
    }

    /** How present the death page's framing (title, tiles, Next Screen) is, 0..1. */
    double pageAlpha(long now) {
        return switch (phase(now)) {
            case POINTS -> 1;
            case DRAIN -> 1 - ramp(now - drainAt, 0, FRAME_OUT_MS);
            case UNLOCK -> 0;
        };
    }

    /** How present the menu's own controls (bar, counters, classes) are, 0..1. */
    double menuAlpha(long now) {
        return switch (phase(now)) {
            case POINTS -> 0;
            case DRAIN -> ramp(now - drainAt, MENU_IN_FROM_MS, DRAIN_MS);
            case UNLOCK -> 1;
        };
    }

    /**
     * Whether only the perks that held points are drawn (the points page and the start of the drain); the
     * rest of the tree joins them once the rings have emptied.
     */
    boolean onlyPointed(long now) {
        Phase p = phase(now);
        return p == Phase.POINTS || p == Phase.DRAIN && now - drainAt < DIM_LIFTS_MS;
    }

    /** The menu's controls take input only once they are up. */
    boolean menuLive(long now) {
        return phase(now) == Phase.UNLOCK;
    }

    /** The death recap, opened when the player moves on from the menu. */
    Screen next() {
        return next.get();
    }

    private static double ramp(long t, long from, long to) {
        if (t <= from) return 0;
        if (t >= to) return 1;
        return (t - from) / (double) (to - from);
    }
}
