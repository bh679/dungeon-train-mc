package games.brennan.dungeontrain.editor;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The dev checkout's source tree, located from the game directory — the one place the
 * {@code projectRoot / src/main/resources / writable?} trio is answered.
 *
 * <p>A {@code ./gradlew runClient} session has {@link FMLPaths#GAMEDIR} at {@code <checkout>/run},
 * so the checkout is its parent. A packaged install has no such parent worth writing to; every
 * query here is null-safe on both the game dir and its parent (the strictest of the three contracts
 * the per-store copies used to carry), and {@link #available()} additionally requires the resources
 * directory to exist and be writable.</p>
 *
 * <p>Stores take a {@link Locator} so a unit test can point one at a temp directory; production code
 * uses the static methods, which read {@link FMLPaths}.</p>
 */
public final class SourceTree {

    /** Where the checkout is — {@link #projectRootOrNull()} by default, a temp dir under test. */
    @FunctionalInterface
    public interface Locator {
        Path projectRootOrNull();
    }

    private static final String RESOURCES = "src/main/resources";
    private static final String NO_ROOT = "Cannot resolve source directory — FMLPaths.GAMEDIR has no parent.";

    /** The production locator: {@link FMLPaths#GAMEDIR}'s parent. */
    public static final Locator DEFAULT = SourceTree::projectRootOrNull;

    private static volatile Path projectRootForTest;

    private SourceTree() {}

    /** The checkout root, or null outside a dev checkout. */
    public static Path projectRootOrNull() {
        Path override = projectRootForTest;
        if (override != null) return override;
        Path gameDir = FMLPaths.GAMEDIR.get();
        return gameDir == null ? null : gameDir.getParent();
    }

    /** The checkout root; throws when there is none — check {@link #available()} first. */
    public static Path projectRoot() {
        return projectRoot(DEFAULT);
    }

    /** {@code <checkout>/src/main/resources}, or null outside a dev checkout. */
    public static Path resourcesRootOrNull() {
        return resourcesRootOrNull(DEFAULT);
    }

    /** True iff the source resources directory exists and is writable. */
    public static boolean available() {
        return available(DEFAULT);
    }

    /** {@code <checkout>/<relPath>}; throws outside a dev checkout. */
    public static Path resolve(String relPath) {
        return resolve(DEFAULT, relPath);
    }

    // ---- Locator-parameterised twins, for stores built against an injected tree ----------------

    public static Path projectRoot(Locator locator) {
        Path root = locator.projectRootOrNull();
        if (root == null) throw new IllegalStateException(NO_ROOT);
        return root;
    }

    public static Path resourcesRootOrNull(Locator locator) {
        Path root = locator.projectRootOrNull();
        return root == null ? null : root.resolve(RESOURCES);
    }

    public static boolean available(Locator locator) {
        Path resources = resourcesRootOrNull(locator);
        return resources != null && Files.isDirectory(resources) && Files.isWritable(resources);
    }

    public static Path resolve(Locator locator, String relPath) {
        return projectRoot(locator).resolve(relPath);
    }

    /** Test seam: pin the checkout root (null restores {@link FMLPaths}). */
    static void setProjectRootForTest(Path root) {
        projectRootForTest = root;
    }
}
