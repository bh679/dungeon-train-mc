package games.brennan.dungeontrain.editor;

/**
 * What a {@link ThreeTierStore} leaves in its cache after {@code delete}.
 *
 * <p>{@link #REMOVE} is the default and the right answer for anything with a bundled tier: the next
 * read consults the classpath again, so deleting a user override of a shipped id resolves to the
 * shipped copy at once. {@link #TOMBSTONE} caches "absent" and is only for a store that has decided
 * a deleted key must read as gone until the next reload.</p>
 */
public enum DeleteCachePolicy {
    REMOVE,
    TOMBSTONE
}
