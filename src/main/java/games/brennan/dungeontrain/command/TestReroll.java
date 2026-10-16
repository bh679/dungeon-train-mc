package games.brennan.dungeontrain.command;

/**
 * What a re-stamp of the running test rolls afresh. The "main" template is the one under test —
 * the shell in a carriage test, the contents in a contents test, the piece in a track test, the room
 * (or the frame) in a dimensional-carriage test; the "others" are everything stood up around it.
 */
public enum TestReroll {
    /** Everything — the Reseed button. */
    ALL,
    /** Only the template under test; the rest keeps its roll — Shift+Reseed. */
    MAIN,
    /** Everything around the template under test; it keeps its own roll. */
    OTHERS,
    /**
     * Nothing: the same rolls stood up again — Reload, and what a changed test setting does, so the
     * setting is the only thing that differs.
     */
    NONE;

    /** Whether the template under test keeps the roll it has. */
    public boolean keepsMain() { return this == OTHERS || this == NONE; }

    /** Whether what stands around it keeps the roll it has. */
    public boolean keepsOthers() { return this == MAIN || this == NONE; }
}
