package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The user-tier files of one template, as they were, and the means to put them back.
 *
 * <p>Save-as writes the player's edits onto the shipped template just long enough to copy them to
 * the new name. This is the "just long enough": taken before that write, restored after it, so the
 * shipped template ends exactly as it started — a file that was there comes back byte for byte, and
 * one that was not is deleted again, which returns the template to the jar's copy.</p>
 */
public final class EditorSaveAsFiles {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Each file's contents at capture; a null array means the file did not exist. */
    private final Map<Path, byte[]> files;

    private EditorSaveAsFiles(Map<Path, byte[]> files) {
        this.files = files;
    }

    /**
     * Record {@code paths} as they are now — or as {@code baseline} says they were, for a file that
     * has already taken edits this session (see {@link EditorSidecarBaseline}).
     */
    public static EditorSaveAsFiles capture(Collection<Path> paths,
                                            Function<Path, Optional<EditorSidecarBaseline.Recorded>> baseline)
            throws IOException {
        Map<Path, byte[]> out = new LinkedHashMap<>();
        for (Path path : paths) {
            if (path == null || out.containsKey(path)) continue;
            Optional<EditorSidecarBaseline.Recorded> recorded = baseline.apply(path);
            if (recorded.isPresent()) {
                out.put(path, recorded.get().bytes());
            } else {
                out.put(path, Files.isRegularFile(path) ? Files.readAllBytes(path) : null);
            }
        }
        return new EditorSaveAsFiles(out);
    }

    /**
     * Put every file back. Carries on past a failure so one stuck file does not leave the rest
     * overwritten; returns false when anything could not be restored, which the caller reports.
     */
    public boolean restore() {
        boolean ok = true;
        for (Map.Entry<Path, byte[]> e : files.entrySet()) {
            try {
                if (e.getValue() == null) {
                    Files.deleteIfExists(e.getKey());
                } else {
                    Files.createDirectories(e.getKey().getParent());
                    Files.write(e.getKey(), e.getValue());
                }
            } catch (IOException ex) {
                ok = false;
                LOGGER.error("[DungeonTrain] Save-as: could not restore {} — {}", e.getKey(), ex.toString());
            }
        }
        return ok;
    }

    /** The paths captured, for logs and tests. */
    public java.util.Set<Path> paths() {
        return files.keySet();
    }
}
