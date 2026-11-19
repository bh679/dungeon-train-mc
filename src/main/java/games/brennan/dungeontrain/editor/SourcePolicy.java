package games.brennan.dungeontrain.editor;

/**
 * When a {@link ThreeTierStore} mirrors a {@code save} into, and a {@code delete} out of, the dev
 * checkout's {@code src/main/resources}. Explicit {@code saveToSource}/{@code promote} calls are
 * unaffected — this only governs the best-effort write-through riding along with the config write.
 *
 * <p>Picked per store, on purpose: the review found the same gate spelled three different ways,
 * and two sidecar stores writing per-install files into the source tree whenever it happened to be
 * writable.</p>
 */
public enum SourcePolicy {
    /** Saves and deletes never touch the source tree. The editor promotes explicitly. */
    NEVER,
    /** Whenever the source tree is writable. */
    ALWAYS,
    /** When editor dev mode is on and the source tree is writable. */
    DEV_MODE,
    /**
     * When the source tree is writable and this key's ship marker (by default the key's
     * {@code .nbt}) is already in the source directory — i.e. the thing the sidecar belongs to
     * ships with the game, so its sidecar should travel with it.
     */
    SHIPS_WITH_GAME
}
