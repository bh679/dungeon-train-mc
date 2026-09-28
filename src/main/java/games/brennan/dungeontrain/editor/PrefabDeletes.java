package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Who may delete a saved prefab, and the delete itself — backs Cmd-click in the creative menu's
 * prefab tabs.
 *
 * <p>Prefabs carry no author, so "yours" is "saved on this install": the file lives in the active
 * write tier ({@link UserContentPaths.Provenance#USER}). Bundled prefabs and ones from an imported
 * package belong to someone else. The dev (editor dev mode in a writable checkout) may delete any
 * prefab, and the delete then takes the source-tree copy and its classpath twin with it
 * ({@link SourceTreeFiles#deleteWithClasspathTwin}) so the running game agrees.</p>
 */
public final class PrefabDeletes {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String EXT = ".json";

    /** Which prefab library an id belongs to. Ordinal is on the wire — append only. */
    public enum Kind { VARIANT, LOOT }

    private PrefabDeletes() {}

    /** Whether the prefab {@code id} of {@code kind} may be deleted on this install. */
    public static boolean canDelete(Kind kind, String id) {
        if (!isValidName(kind, id)) return false;
        return decide(isUserTier(kind, id), devMode(kind), devCanReach(kind, id));
    }

    /**
     * The rule, free of the filesystem: yours, or the dev reaching a copy the dev owns (the
     * source tree or the user tier).
     */
    static boolean decide(boolean userTier, boolean devMode, boolean inSourceTree) {
        if (userTier) return true;
        return devMode && inSourceTree;
    }

    /**
     * Delete the config-tier copy and, in dev mode, the source-tree copy too.
     *
     * @return whether any file was removed
     */
    public static boolean delete(Kind kind, String id) throws IOException {
        boolean sourceRemoved = false;
        if (devMode(kind)) {
            sourceRemoved = SourceTreeFiles.deleteWithClasspathTwin(sourceFileFor(kind, id));
        }
        // After the classpath twin is gone the store's hasBundled is false, so its delete drops
        // the id from the registry instead of re-exposing a stale bundled copy.
        boolean configRemoved = switch (kind) {
            case VARIANT -> BlockVariantPrefabStore.delete(id);
            case LOOT -> LootPrefabStore.delete(id);
        };
        LOGGER.info("[DungeonTrain] Prefab delete {} '{}' — config removed: {}, source removed: {}",
            kind, id, configRemoved, sourceRemoved);
        return configRemoved || sourceRemoved;
    }

    public static boolean isValidName(Kind kind, String id) {
        return switch (kind) {
            case VARIANT -> BlockVariantPrefabStore.isValidName(id);
            case LOOT -> LootPrefabStore.isValidName(id);
        };
    }

    private static boolean isUserTier(Kind kind, String id) {
        return UserContentPaths.provenanceOf(subdir(kind), id + EXT) == UserContentPaths.Provenance.USER;
    }

    private static boolean devMode(Kind kind) {
        if (!EditorDevMode.isEnabled()) return false;
        return switch (kind) {
            case VARIANT -> BlockVariantPrefabStore.sourceTreeAvailable();
            case LOOT -> LootPrefabStore.sourceTreeAvailable();
        };
    }

    /** In a dev checkout the bundled tier IS the source tree, so a bundled prefab is reachable. */
    private static boolean devCanReach(Kind kind, String id) {
        Path source = sourceFileFor(kind, id);
        return source != null && Files.isRegularFile(source);
    }

    private static Path sourceFileFor(Kind kind, String id) {
        try {
            return switch (kind) {
                case VARIANT -> BlockVariantPrefabStore.sourceFileFor(id);
                case LOOT -> LootPrefabStore.sourceFileFor(id);
            };
        } catch (RuntimeException e) {
            // Outside a checkout the source-path helpers have no root to resolve against.
            return null;
        }
    }

    private static String subdir(Kind kind) {
        return switch (kind) {
            case VARIANT -> BlockVariantPrefabStore.SUBDIR;
            case LOOT -> LootPrefabStore.SUBDIR;
        };
    }
}
