package games.brennan.dungeontrain.client.live;

import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * A stream that carries on after its streamer died, through the death screens ("post mortem").
 *
 * <p>The server still burns the Live Eye at death and says {@code STOP_DIED}; a client that is already
 * broadcasting keeps going on the same relay claim instead of stopping, and asks the streamer
 * ({@link LivePostMortemChoiceScreen}) whether to end it or stream on. It ends on that choice, on the
 * End Stream button ({@link LiveRecOverlay}), on leaving the world, on a respawn, when a viewer takes
 * the stream over (the relay cuts this one off), or when no Dungeon Train death screen shows up at all
 * (a Train Builder world or another server keeps vanilla's).</p>
 *
 * <p>Render-thread state, held by {@link LiveStreamController}; the decisions are pure so they can be tested.</p>
 */
final class LivePostMortem {

    enum Phase { NONE, CHOOSING, CONTINUING }

    /** How long after death a Dungeon Train death screen has to show up before the stream ends as before. */
    static final int SCREEN_GRACE_TICKS = 40;

    /** The death screens, by class name (no compile-time link back into client/). */
    static final Set<String> DEATH_SCREENS = Set.of(
        "games.brennan.dungeontrain.client.DeathMomentScreen",
        "games.brennan.dungeontrain.client.live.LivePostMortemChoiceScreen",
        "games.brennan.dungeontrain.client.NarrativeDeathScreen");

    private Phase phase = Phase.NONE;
    private int ticks;
    private boolean sawDeathScreen;
    private boolean sawDead;

    Phase phase() {
        return phase;
    }

    boolean active() {
        return phase != Phase.NONE;
    }

    void begin() {
        phase = Phase.CHOOSING;
        ticks = 0;
        sawDeathScreen = false;
        sawDead = false;
    }

    void choseContinue() {
        if (phase == Phase.CHOOSING) phase = Phase.CONTINUING;
    }

    void clear() {
        phase = Phase.NONE;
    }

    /**
     * One client tick while active. True when the stream should end: no death screen came up in
     * time, or the player is alive again after having been seen dead (a server respawn).
     */
    boolean tick(@Nullable Class<?> screen, boolean playerDead, boolean playerAlive) {
        if (phase == Phase.NONE) return false;
        ticks++;
        if (playerDead) sawDead = true;
        if (screen != null && isDeathScreen(screen.getName())) sawDeathScreen = true;
        return shouldEnd(ticks, sawDeathScreen, sawDead, playerAlive);
    }

    static boolean shouldEnd(int ticksSinceDeath, boolean sawDeathScreen, boolean sawDead, boolean playerAlive) {
        if (!sawDeathScreen && ticksSinceDeath >= SCREEN_GRACE_TICKS) return true;
        return sawDead && playerAlive;
    }

    static boolean isDeathScreen(@Nullable String className) {
        return className != null && DEATH_SCREENS.contains(className);
    }
}
