package games.brennan.dungeontrain.editor;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Where one block-variant sidecar lives and how it starts out — everything
 * {@link VariantSidecarStore} needs to load, crop, save, carry and forget a {@link VariantDoc}
 * without knowing which kind of template it belongs to.
 *
 * <p>One record per kind implements it (see the {@code address(...)} factories on
 * {@code CarriageVariantBlocks}, {@code CarriageContentsVariantBlocks}, {@code CarriagePartVariantBlocks},
 * {@code WholeVariantBlocks} and {@code TrackVariantBlocks}). Two addresses with the same
 * {@link #cacheKey} name the same sidecar.</p>
 */
public interface VariantAddress {

    /** The sidecar file extension every kind shares. */
    String EXT = ".variants.json";

    /** Session-cache key, {@code <kind>:<name>}; also the prefix the once-per-footprint warnings are keyed on. */
    String cacheKey();

    /** Log context for this sidecar's lines, e.g. {@code portal_room:basic}. */
    default String contextId() {
        return cacheKey();
    }

    /** The {@link UserContentPaths#findFile} sub-slug the sidecar is read from, across every tier. */
    String userSubdir();

    /** The sidecar's basename, {@code <name>.variants.json}. */
    String fileName();

    /** Where a save writes the user-tier sidecar — the active package's folder for this kind. */
    Path userPath();

    /**
     * The user-tier file a load reads, across every tier ({@link UserContentPaths#findFile}: user
     * first, then imported packages), or null when none exists. Overridable so a test can stand a
     * temp folder in for the install.
     */
    default @Nullable Path findUserFile() {
        return UserContentPaths.findFile(userSubdir(), fileName());
    }

    /** Classpath resource of the bundled sidecar, {@code /data/dungeontrain/...}. */
    String bundledResource();

    /**
     * Source-tree path the dev-mode write-through promotes to, or null where the checkout is not
     * present or not writable (a production install).
     */
    @Nullable Path sourcePath();

    /** The mirror axes an absent {@code "mirror"} field means for this kind. */
    default MirrorDefaults defaultMirror() {
        return MirrorDefaults.NONE;
    }

    /** How this kind's cells roll at spawn time once narrowed to the tier. */
    default VariantPick pick() {
        return VariantPick.PLAIN;
    }

    /**
     * Run after every write the store makes for this address — save, delete, rename, invalidate —
     * so an index built over this kind's sidecars can drop what it computed. Only parts have one
     * today ({@link StageBlockIndex#invalidateAll()}: stages link to parts and nothing else).
     */
    default void afterWrite() {
    }
}
