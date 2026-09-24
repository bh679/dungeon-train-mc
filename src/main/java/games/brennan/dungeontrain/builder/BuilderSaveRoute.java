package games.brennan.dungeontrain.builder;

/**
 * What pressing Save in the Train Builder does, before anything is written.
 *
 * <p>Two reasons a save cannot simply write. A draft has no name, so there is nowhere to write it.
 * A build that goes by a shipped name has somewhere to write, but writing there quietly overwrites
 * the mod's own template and could send it to My Builds under that name — so the player is offered a
 * name of their own first, with keeping it as a local edit as the other choice.</p>
 *
 * <p>Pure, and its own class, because every Save button in the Builder has to agree on this, and a
 * button that forgot the second case is exactly the bug this exists to close.</p>
 */
public enum BuilderSaveRoute {
    /** Write straight away under the build's own name. */
    SAVE,
    /** An unnamed draft: ask for a name, and there is no other way to save. */
    NAME_REQUIRED,
    /** A shipped template: offer a new name, or keeping it as a local edit that is not uploaded. */
    NAME_OR_LOCAL;

    /**
     * @param draft   the build has no name yet
     * @param builtin the build goes by a shipped name outside a dev checkout, as the server reports
     */
    public static BuilderSaveRoute of(boolean draft, boolean builtin) {
        if (draft) return NAME_REQUIRED;
        return builtin ? NAME_OR_LOCAL : SAVE;
    }
}
