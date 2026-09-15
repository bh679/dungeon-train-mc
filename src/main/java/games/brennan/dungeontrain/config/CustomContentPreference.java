package games.brennan.dungeontrain.config;

import games.brennan.dungeontrain.world.CustomContentChoice;

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
    DISABLE,
    /** Dev builds only: keep the content and stay Live. See {@link CustomContentChoice#DEV_IGNORE}. */
    DEV_IGNORE;

    /** Should the prompt actually be shown, or can it be answered without the player? */
    public boolean asks() {
        return this == ASK;
    }

    /** Does this answer leave the custom content loading? Anything but {@link #DISABLE}. */
    public boolean keepsContent() {
        return this != DISABLE;
    }

    /** The world answer to record when {@link #asks()} is false. */
    public CustomContentChoice toChoice() {
        return switch (this) {
            case DISABLE -> CustomContentChoice.DISABLE;
            case DEV_IGNORE -> CustomContentChoice.DEV_IGNORE;
            case ASK, CONTINUE -> CustomContentChoice.ALLOW;
        };
    }

    /** The preference that reproduces a given world answer — what "Remember decision" stores. */
    public static CustomContentPreference fromChoice(CustomContentChoice choice) {
        return switch (choice) {
            case DISABLE -> DISABLE;
            case DEV_IGNORE -> DEV_IGNORE;
            case ALLOW, UNSET -> CONTINUE;
        };
    }
}
