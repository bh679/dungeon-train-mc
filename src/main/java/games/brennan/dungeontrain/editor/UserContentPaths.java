package games.brennan.dungeontrain.editor;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * Single source of truth for the per-install user-content root.
 *
 * <p>All player-authored content (carriages, parts, contents, container loot,
 * track-side, prefabs, weights overrides) is stored under
 * {@code <minecraft>/config/dungeontrain/user/<subdir>/}. The {@code user/}
 * segment exists solely so the player can see at a glance which files came
 * with the mod jar (none — the jar's bundled data lives on the classpath)
 * and which they themselves authored. It also makes the
 * {@link UserContentExporter} a one-line directory walk.</p>
 *
 * <p>Keeping the literal {@code "user"} segment in one place means there is
 * exactly one spot to update if the layout ever shifts again — every store
 * goes through {@link #dir(String)} rather than concatenating its own path.</p>
 *
 * <p>Out of scope: per-world ship persistence ({@code carriage-persist/}),
 * engine TOML configs ({@code dungeontrain-client.toml},
 * {@code dungeontrain-server.toml}), bundled classpath data
 * ({@code /data/dungeontrain/...}), and dev-mode source-tree writes
 * ({@code src/main/resources/data/dungeontrain/...}). None of these are user
 * content — they belong outside this folder.</p>
 */
public final class UserContentPaths {

    private static final String DUNGEONTRAIN = "dungeontrain";
    private static final String USER = "user";

    private UserContentPaths() {}

    /**
     * {@code <config>/dungeontrain/user/} — the root the exporter walks and
     * the migration writes into.
     */
    public static Path root() {
        return FMLPaths.CONFIGDIR.get().resolve(DUNGEONTRAIN).resolve(USER);
    }

    /**
     * {@code <config>/dungeontrain/user/<subSlug>} where {@code subSlug} is
     * a kind-specific sub-path like {@code "templates"},
     * {@code "prefabs/loot"}, or {@code "parts/cab"}.
     */
    public static Path dir(String subSlug) {
        return root().resolve(subSlug);
    }

    /**
     * {@code <config>/dungeontrain/} — the legacy root used pre-0.125 and
     * still home to engine config / per-world data. Exposed only so the
     * {@link UserContentMigration} helper can locate the legacy layout when
     * moving files; stores should not write here.
     */
    public static Path legacyRoot() {
        return FMLPaths.CONFIGDIR.get().resolve(DUNGEONTRAIN);
    }

    /**
     * Pre-0.125 location for {@code subSlug}. Used by
     * {@link UserContentMigration} to find files to move; not a write target.
     */
    public static Path legacyDir(String subSlug) {
        return legacyRoot().resolve(subSlug);
    }
}
