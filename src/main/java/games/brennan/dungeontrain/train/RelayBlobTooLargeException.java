package games.brennan.dungeontrain.train;

import java.io.IOException;

/**
 * A relay blob over {@link RelayBlobLimits}: too long to decode, or bigger decoded than the budget.
 *
 * <p>An {@link IOException}, so every caller's existing "this blob is unusable" handling applies:
 * a lease is handed back and the slot builds fresh, a builder download reports a failure.</p>
 */
public final class RelayBlobTooLargeException extends IOException {

    private final boolean wholeBuild;

    /**
     * @param wholeBuild true when the build as a whole is over (its fold budget is spent, or it has
     *                   too many deltas), false when only this one blob is
     */
    public RelayBlobTooLargeException(String message, boolean wholeBuild) {
        super(message);
        this.wholeBuild = wholeBuild;
    }

    /** True when the whole build must be refused, not just this blob skipped. */
    public boolean wholeBuild() {
        return wholeBuild;
    }
}
