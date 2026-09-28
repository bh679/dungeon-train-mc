package games.brennan.dungeontrain.world;

/**
 * This world's answer to the "you have custom Train Editor content" prompt.
 *
 * <p>Persisted on {@link DungeonTrainWorldData}. {@link #UNSET} is what makes
 * the prompt fire on join — once a player has answered, the world never asks
 * again, whichever way they answered.</p>
 *
 * <ul>
 *   <li>{@link #UNSET} — nobody has answered yet (also every world saved
 *       before this feature landed). Custom content loads normally, and the
 *       run is Free Play while it does, exactly as {@link #ALLOW}; the only
 *       difference is that the prompt still appears.</li>
 *   <li>{@link #ALLOW} — play with the custom content. The run is Free Play
 *       for as long as custom content is actually present
 *       ({@code EditorContentIntegrity}), so removing the packages restores
 *       normal play with no world edit.</li>
 *   <li>{@link #DISABLE} — suppress every enabled package for this world.
 *       {@code UserContentPaths.searchDirs} goes empty, so all content
 *       resolves to the bundled classpath tier and the run is not Free Play.</li>
 *   <li>{@link #DEV_IGNORE} — the developer's answer: play with the custom
 *       content <em>and</em> stay Live. Honoured only on a dev build
 *       ({@code DungeonTrain.isDevBuild()}); a release build reads it exactly as
 *       {@link #ALLOW}, so a save or a remembered preference carried across can't
 *       hand anyone a counting run on custom content.</li>
 * </ul>
 */
public enum CustomContentChoice {
    UNSET("unset"),
    ALLOW("allow"),
    DISABLE("disable"),
    DEV_IGNORE("dev_ignore");

    private final String nbtId;

    CustomContentChoice(String nbtId) {
        this.nbtId = nbtId;
    }

    public String nbtId() {
        return nbtId;
    }

    /** Has the player answered the prompt for this world? */
    public boolean isAnswered() {
        return this != UNSET;
    }

    /** Is custom content suppressed for this world? */
    public boolean suppressesContent() {
        return this == DISABLE;
    }

    /**
     * Does this answer ask for the custom-content Free Play taint to be waived? Only
     * {@link #DEV_IGNORE}, and only meaningful on a dev build — callers pair it with
     * {@code DungeonTrain.isDevBuild()}.
     */
    public boolean exemptsFreePlay() {
        return this == DEV_IGNORE;
    }

    /** Unknown / absent ids read as {@link #UNSET} — a legacy world simply hasn't answered. */
    public static CustomContentChoice fromNbt(String s) {
        if (s == null) return UNSET;
        for (CustomContentChoice c : values()) {
            if (c.nbtId.equals(s)) return c;
        }
        return UNSET;
    }
}
