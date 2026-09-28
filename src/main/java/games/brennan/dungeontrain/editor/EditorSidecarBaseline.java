package games.brennan.dungeontrain.editor;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What a template's block-variant sidecar held before this session's first Z-menu edit to it.
 *
 * <p>The Z menu writes its sidecar to disk on every edit, so by the time the player presses Save the
 * file already holds the edits. Save-as needs the file as it was <em>before</em> them, to put the
 * shipped template back the way it was last saved — so the first edit to a sidecar records what was
 * there, and a stamp or a save (after which the file on disk is the baseline again) forgets it.</p>
 *
 * <p>Keyed by the plot's dirty-scan key where it has one — that is what a stamp or save forgets by —
 * and by the file itself for a part, whose plots have no dirty-scan row.</p>
 *
 * <p>In memory only: after a restart there is nothing recorded and the file on disk is the
 * baseline. That is the same horizon the dirty scan itself has.</p>
 */
public final class EditorSidecarBaseline {

    /** A file's recorded contents; {@code bytes} null means the file did not exist. */
    public record Recorded(@Nullable byte[] bytes) {
        public boolean existed() { return bytes != null; }
    }

    private static final Map<String, Map<Path, Recorded>> BY_KEY = new HashMap<>();

    private EditorSidecarBaseline() {}

    /**
     * Record {@code file} as it is now, unless something is already recorded for it under
     * {@code key} — the first edit's view is the baseline, not the latest one's.
     */
    public static synchronized void remember(@Nullable String key, @Nullable Path file) {
        if (file == null) return;
        String k = keyOf(key, file);
        BY_KEY.computeIfAbsent(k, x -> new HashMap<>()).computeIfAbsent(file, EditorSidecarBaseline::read);
    }

    /** What was recorded for {@code file}, if anything. */
    public static synchronized Optional<Recorded> lookup(Path file) {
        for (Map<Path, Recorded> files : BY_KEY.values()) {
            Recorded r = files.get(file);
            if (r != null) return Optional.of(r);
        }
        return Optional.empty();
    }

    /** The plot under {@code key} was stamped or saved: the disk is its baseline again. */
    public static synchronized void forget(@Nullable String key) {
        if (key != null) BY_KEY.remove(key);
    }

    /** As {@link #forget}, for a plot with no dirty-scan key. */
    public static synchronized void forgetFile(@Nullable Path file) {
        if (file == null) return;
        BY_KEY.remove(keyOf(null, file));
        for (Map<Path, Recorded> files : BY_KEY.values()) files.remove(file);
    }

    /** Test and server-stop hook. */
    public static synchronized void clearAll() {
        BY_KEY.clear();
    }

    private static String keyOf(@Nullable String key, Path file) {
        return key != null ? key : "file:" + file.toAbsolutePath();
    }

    private static Recorded read(Path file) {
        try {
            return Files.isRegularFile(file) ? new Recorded(Files.readAllBytes(file)) : new Recorded(null);
        } catch (IOException e) {
            // Unreadable reads as absent: Save-as would then delete the file on restore, which puts
            // the template back on its shipped sidecar — the safer of the two wrong answers.
            return new Recorded(null);
        }
    }
}
