package games.brennan.dungeontrain.config;

/**
 * This client's remembered answer to the "you have custom Train Editor content"
 * prompt shown when a world starts.
 *
 * <p>{@link #ASK} (the default) shows the prompt every time a world with custom
 * content is entered for the first time. Ticking "Remember decision" on the
 * prompt stores {@link #CONTINUE} or {@link #DISABLE} instead, and the prompt is
 * answered silently from then on. Changeable in Options → Dungeon Train.</p>
 *
 * <p>Client-scoped on purpose: it is a preference about being interrupted, not a
 * gameplay rule. The authoritative decision for a given world is the
 * {@code CustomContentChoice} stored on that world's SavedData.</p>
 */
public enum CustomContentPreference {
    ASK,
    CONTINUE,
    DISABLE;

    /** Should the prompt actually be shown, or can it be answered without the player? */
    public boolean asks() {
        return this == ASK;
    }

    /** The answer to send when {@link #asks()} is false. */
    public boolean keepsContent() {
        return this == CONTINUE;
    }
}
