package games.brennan.dungeontrain.config;

import java.util.function.Consumer;

/**
 * What the player did to one switch on the consent card while it was open.
 *
 * <p>The card's switches start from a per-mode value and the player may or may not click them.
 * Discord Presence reports each click as it happens, then — as the card closes — the state every
 * switch of the confirmed mode shows, and only then the chosen mode. So the final value is decided
 * here, once, at close: <em>the last report made under the confirmed mode, otherwise the fallback for
 * that mode</em>.</p>
 *
 * <p>Each report carries the mode whose switch sent it. The card rebuilds its lines (and asks Dungeon
 * Train for them again) on every re-init — a pill click included — so a draft cannot be reset there
 * without losing the click; keying the report by mode is what stops an Adult-mode click from leaking
 * into a Kid-mode confirm instead.</p>
 */
public final class ConsentDraft {

    private final Consumer<Boolean> sink;
    private ContentMode touchedMode;
    private boolean touched;

    /** @param sink where the resolved value is written when the card closes */
    public ConsentDraft(Consumer<Boolean> sink) {
        this.sink = sink;
    }

    /** A switch shown under {@code mode} now reads {@code value} (a click, or its state at close). */
    public void touch(ContentMode mode, boolean value) {
        touchedMode = mode;
        touched = value;
    }

    /** The card closed on {@code picked}; {@code fallback} is that mode's value for an unreported switch. */
    public void commit(ContentMode picked, boolean fallback) {
        sink.accept(resolve(touchedMode == picked ? touched : null, fallback));
        touchedMode = null;
    }

    /** The last report under the confirmed mode wins; with none the fallback does. Pure, for the tests. */
    static boolean resolve(Boolean touched, boolean fallback) {
        return touched != null ? touched : fallback;
    }
}
