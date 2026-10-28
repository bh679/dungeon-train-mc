package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.template.SaveResult;
import org.slf4j.Logger;

import java.io.IOException;

/**
 * The dev-mode half of every editor save: after the config-dir write has succeeded, write the same
 * thing through to the source tree so it ships in the next build, and report how that went.
 *
 * <p>Skipped outright when {@link EditorDevMode} is off. The primary write decides the result; a
 * sidecar (variant pools, container links) that fails to reach the source tree is logged and does
 * not fail the save, since the template itself got there.</p>
 */
final class EditorSave {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A write that may fail with an {@link IOException}. */
    @FunctionalInterface
    interface IoAction {
        void run() throws IOException;
    }

    private EditorSave() {}

    /**
     * @param what     what is being saved, for the log line ({@code "Track editor save: tile/default"})
     * @param primary  the template's own source-tree write
     * @param sidecars best-effort companion writes, each logged on failure and otherwise ignored
     */
    static SaveResult toSource(String what, IoAction primary, IoAction... sidecars) {
        if (!EditorDevMode.isEnabled()) return SaveResult.skipped();
        try {
            primary.run();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] {}: source write failed: {}", what, e.toString());
            return SaveResult.failed(e.getMessage());
        }
        for (IoAction sidecar : sidecars) {
            try {
                sidecar.run();
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] {}: sidecar source write failed: {}", what, e.toString());
            }
        }
        return SaveResult.written();
    }
}
