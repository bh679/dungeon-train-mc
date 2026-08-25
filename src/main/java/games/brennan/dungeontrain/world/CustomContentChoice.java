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
 * </ul>
 */
public enum CustomContentChoice {
    UNSET("unset"),
    ALLOW("allow"),
    DISABLE("disable");

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

    /** Unknown / absent ids read as {@link #UNSET} — a legacy world simply hasn't answered. */
    public static CustomContentChoice fromNbt(String s) {
        if (s == null) return UNSET;
        for (CustomContentChoice c : values()) {
            if (c.nbtId.equals(s)) return c;
        }
        return UNSET;
    }
}
