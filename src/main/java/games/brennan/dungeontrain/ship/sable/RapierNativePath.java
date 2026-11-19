package games.brennan.dungeontrain.ship.sable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Picks the file Sable extracts its Rapier natives to, so a locked copy can't crash startup.
 *
 * <p>Sable's {@code Rapier3D.loadLibrary} always deletes {@code .sable/natives/<native>} and
 * extracts it again. On Windows a DLL still loaded by another process (a crashed {@code javaw.exe}
 * left running, a second instance, antivirus) can't be deleted, the delete throws
 * {@code AccessDeniedException} and Sable turns that into a "Sable linking with Rapier natives"
 * crash. {@link #choose} does that delete first; if it fails it hands back a per-process sibling
 * ({@code sable_rapier_x86_64_windows.dt-<pid>.dll}) so Sable extracts fresh bytes there instead.
 * See {@code mixin/RapierNativeLockedDllMixin}.</p>
 */
public final class RapierNativePath {

    private static final Logger LOGGER = LoggerFactory.getLogger(RapierNativePath.class);

    /** Marks our per-process copies: {@code <stem>.dt-<pid><ext>}. */
    static final String FALLBACK_TAG = ".dt-";

    @FunctionalInterface
    public interface Deleter {
        void delete(Path path) throws IOException;
    }

    private RapierNativePath() {}

    /** Production entry point: real deletes, this process's pid. */
    public static Path choose(final Path canonical) {
        return choose(canonical, Files::delete, ProcessHandle.current().pid());
    }

    /**
     * Returns {@code canonical} when it is absent or could be deleted (Sable's own behaviour), else
     * a per-process sibling. Never throws: anything unexpected returns {@code canonical}, which is
     * no worse than Sable without this guard.
     */
    static Path choose(final Path canonical, final Deleter deleter, final long pid) {
        try {
            removeStaleFallbacks(canonical, deleter);
            if (!Files.exists(canonical)) {
                return canonical;
            }
            try {
                deleter.delete(canonical);
                return canonical;
            } catch (final IOException locked) {
                final Path fallback = fallbackFor(canonical, pid);
                LOGGER.warn("Sable Rapier natives {} can't be replaced ({}), most likely still loaded by "
                        + "another Minecraft process; extracting to {} instead",
                        canonical, locked.toString(), fallback.getFileName());
                return fallback;
            }
        } catch (final RuntimeException unexpected) {
            LOGGER.warn("Couldn't check Sable Rapier natives {}; leaving Sable's path as is", canonical, unexpected);
            return canonical;
        }
    }

    /** {@code dir/sable_rapier_x86_64_windows.dll} → {@code dir/sable_rapier_x86_64_windows.dt-<pid>.dll}. */
    static Path fallbackFor(final Path canonical, final long pid) {
        final String name = canonical.getFileName().toString();
        final int dot = name.lastIndexOf('.');
        final String stem = dot > 0 ? name.substring(0, dot) : name;
        final String ext = dot > 0 ? name.substring(dot) : "";
        return canonical.resolveSibling(stem + FALLBACK_TAG + pid + ext);
    }

    /** Best effort: drops earlier per-process copies; ones still loaded stay until next time. */
    private static void removeStaleFallbacks(final Path canonical, final Deleter deleter) {
        final Path dir = canonical.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        final String name = canonical.getFileName().toString();
        final int dot = name.lastIndexOf('.');
        final String glob = (dot > 0 ? name.substring(0, dot) : name) + FALLBACK_TAG + "*";
        try (DirectoryStream<Path> stale = Files.newDirectoryStream(dir, glob)) {
            for (final Path old : stale) {
                try {
                    deleter.delete(old);
                } catch (final IOException stillLoaded) {
                    // Locked by the process that extracted it; the next launch tries again.
                }
            }
        } catch (final IOException listing) {
            LOGGER.debug("Couldn't list {} for stale Sable natives", dir, listing);
        }
    }
}
