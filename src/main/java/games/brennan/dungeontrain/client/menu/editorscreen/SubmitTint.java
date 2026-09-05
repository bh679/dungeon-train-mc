package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import games.brennan.dungeontrain.client.menu.EditorSaveStatus;

/**
 * What colour the toolbar's Submit icon is: <b>pulsing blue</b> while a saved build is waiting to be
 * offered, <b>steady green</b> once it has been.
 *
 * <p>The pulse is the point. A build that is saved and never submitted is invisible — it looks
 * exactly like one already on the train — so the button that would fix that asks for attention until
 * it is pressed, and then stops. Nothing else in this row moves unless something is outstanding.</p>
 *
 * <p>On the same wave as the Save icon's own pulse ({@link EditorSaveStatus#pulse}), so when both are
 * lit they breathe together and read as one screen asking rather than two animations competing.</p>
 *
 * <p>Pure and its own class for the reason the layout records are: a colour that is wrong — green
 * before anything was submitted, say — is a lie about where somebody's work stands, and that is worth
 * a test that needs no client to run.</p>
 */
public final class SubmitTint {

    /**
     * Ready to go, and already submitted.
     *
     * <p>The two colours a build's tile is ringed with for the same two facts, so the row and the grid
     * do not disagree about what blue and green mean.</p>
     */
    static final int READY = BuilderReviewState.BORDER_SUBMITTED;
    static final int SUBMITTED = BuilderReviewState.BORDER_ACCEPTED;

    private SubmitTint() {}

    /** The icon's tint right now — see the class note. */
    public static int of(boolean submitted, long nowMillis) {
        return submitted ? SUBMITTED : EditorSaveStatus.scale(READY, EditorSaveStatus.pulse(nowMillis));
    }
}
