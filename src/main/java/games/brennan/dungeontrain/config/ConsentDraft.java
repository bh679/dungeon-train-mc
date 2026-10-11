package games.brennan.dungeontrain.config;

import java.util.function.Consumer;

/**
 * What the player did to one switch on the first-launch consent card while it was open.
 *
 * <p>The card's switches start from a per-mode default (Livestreaming: on for Adult, off for Kid)
 * and the player may or may not click them. Discord Presence reports clicks as they happen and the
 * chosen mode only when the card closes, so the final value of a switch is decided here, once, at
 * close: <em>the player's last click if there was one, otherwise the default of the mode they
 * confirmed</em>. That is what makes the defaults actually land for a player who never touches a
 * switch, and what stops an Adult-mode click from leaking into a Kid-mode confirm (the card resets
 * its pills when the mode changes, and {@link #reset()} mirrors that).</p>
 */
public final class ConsentDraft {

    private final Consumer<Boolean> sink;
    private Boolean touched;

    /** @param sink where the resolved value is written when the card closes */
    public ConsentDraft(Consumer<Boolean> sink) {
        this.sink = sink;
    }

    /** The player clicked the switch; remember its new state. */
    public void touch(boolean value) {
        touched = value;
    }

    /** The card rebuilt its lines for another mode; earlier clicks no longer apply. */
    public void reset() {
        touched = null;
    }

    /** The card closed on a mode whose default for this switch is {@code modeDefault}. */
    public void commit(boolean modeDefault) {
        sink.accept(resolve(touched, modeDefault));
        touched = null;
    }

    /** The last click wins; with no click the mode's default does. Pure, for the tests. */
    static boolean resolve(Boolean touched, boolean modeDefault) {
        return touched != null ? touched : modeDefault;
    }

    /** True when the player has clicked this switch since the card opened or last changed mode. */
    public boolean isTouched() {
        return touched != null;
    }
}
