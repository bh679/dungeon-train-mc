package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deleting a file from the dev-mode source tree so the <b>running</b> game agrees.
 *
 * <p>A dev-mode delete removes the bundled copy under {@code src/main/resources}, but the dev
 * classpath serves the exploded copy under {@code build/resources/main} (see
 * {@link games.brennan.dungeontrain.util.BundledNbtScanner}), and every store's bundled tier reads
 * from the classpath. Delete only the source file and the next cache miss resurrects the template
 * from the classpath twin until the next {@code processResources} — which is how a promoted
 * dimensional-carriage parent kept its old group in the roster after its files were gone. So a
 * source-tree delete takes the classpath twin with it.</p>
 */
public final class SourceTreeFiles {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Path SOURCE_RESOURCES = Path.of("src", "main", "resources");
    private static final Path BUILD_RESOURCES = Path.of("build", "resources", "main");

    private SourceTreeFiles() {}

    /**
     * Delete {@code sourceFile} and, when it lies under {@code src/main/resources}, the same relative
     * path under {@code build/resources/main}. Tolerates the null the source-path helpers return
     * outside a checkout.
     *
     * @return whether the source file existed
     */
    public static boolean deleteWithClasspathTwin(Path sourceFile) throws IOException {
        if (sourceFile == null) return false;
        boolean existed = Files.deleteIfExists(sourceFile);
        Path twin = classpathTwin(sourceFile);
        if (twin != null) {
            try {
                if (Files.deleteIfExists(twin)) {
                    LOGGER.info("[DungeonTrain] Source delete: removed exploded classpath copy {}", twin);
                }
            } catch (IOException e) {
                // The source delete is the one that matters past this session; a stale classpath copy
                // is a this-run-only nuisance, so it must not fail the delete.
                LOGGER.warn("[DungeonTrain] Source delete: could not remove classpath copy {}: {}", twin, e.toString());
            }
        }
        return existed;
    }

    /**
     * The {@code build/resources/main} path that mirrors {@code sourceFile}, or null when the file is
     * not under a {@code src/main/resources} tree. Pure — exposed for tests.
     */
    static Path classpathTwin(Path sourceFile) {
        Path abs = sourceFile.toAbsolutePath().normalize();
        int n = abs.getNameCount();
        for (int i = 0; i + 3 <= n; i++) {
            if (abs.subpath(i, i + 3).equals(SOURCE_RESOURCES)) {
                Path projectRoot = i == 0 ? abs.getRoot() : abs.getRoot().resolve(abs.subpath(0, i));
                Path relative = abs.subpath(i + 3, n);
                return projectRoot.resolve(BUILD_RESOURCES).resolve(relative);
            }
        }
        return null;
    }
}
