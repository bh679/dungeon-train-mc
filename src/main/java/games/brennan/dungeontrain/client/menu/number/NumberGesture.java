package games.brennan.dungeontrain.client.menu.number;

/**
 * What a click on a number cell means. Every editor number — weights, level bounds, counts, room
 * sizes, builder chips — reads its click through {@link #resolve} so they all answer to the same
 * three gestures:
 *
 * <ul>
 *   <li>click — step up</li>
 *   <li>shift-click or right-click — step down</li>
 *   <li>cmd-click (Ctrl off macOS) — type the value</li>
 * </ul>
 *
 * <p>Cmd wins over everything, so cmd-right-click and cmd-shift-click still type rather than step:
 * the modifier is the deliberate part of the gesture.</p>
 */
public enum NumberGesture {
    INC, DEC, TYPE;

    /** GLFW's button number for the right mouse button. */
    public static final int RIGHT_BUTTON = 1;

    public static NumberGesture resolve(int button, boolean shift, boolean cmd) {
        if (cmd) return TYPE;
        if (shift || button == RIGHT_BUTTON) return DEC;
        return INC;
    }
}
