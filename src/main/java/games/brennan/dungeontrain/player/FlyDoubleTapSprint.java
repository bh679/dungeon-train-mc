package games.brennan.dungeontrain.player;

/**
 * Double-tap-forward-to-sprint while flying.
 *
 * <p>Vanilla 1.21.1 only arms its double-tap window ({@code LocalPlayer.sprintTriggerTime}) when
 * the player is on the ground or underwater, so a player already in creative flight has to hold
 * the sprint key. This is the same rule — two fresh forward presses within {@link #WINDOW_TICKS}
 * — applied in the air. Pure, so the timing is unit-testable; the per-player timer lives in
 * {@code LocalPlayerFlyDoubleTapSprintMixin}.</p>
 */
public final class FlyDoubleTapSprint {

    /** Same window as vanilla's ground double-tap ({@code sprintTriggerTime = 7}). */
    public static final int WINDOW_TICKS = 7;

    /** Timer to carry into the next tick, and whether to start sprinting this tick. */
    public record Step(int timer, boolean sprint) {}

    private FlyDoubleTapSprint() {}

    /**
     * @param timer      ticks left in the window from last tick (0 = not armed)
     * @param wasForward forward held before this tick's input update
     * @param isForward  forward held after it
     * @param eligible   flying, not riding, not already sprinting, and allowed to start sprinting
     */
    public static Step tick(int timer, boolean wasForward, boolean isForward, boolean eligible) {
        if (!eligible) return new Step(0, false);
        int remaining = Math.max(0, timer - 1);
        if (wasForward || !isForward) return new Step(remaining, false);
        return remaining > 0 ? new Step(0, true) : new Step(WINDOW_TICKS, false);
    }
}
