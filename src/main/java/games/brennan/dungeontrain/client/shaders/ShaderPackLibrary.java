package games.brennan.dungeontrain.client.shaders;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.shader.IrisPackControl;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What the game already has on disk, and what is running — the two facts the Shaders page's one
 * action button is derived from.
 *
 * <p>"Installed" is deliberately the exact pinned filename in {@code <gameDir>/shaderpacks/}, not
 * "some build of this pack". A player who already has a different build of BSL is offered the
 * download, which lands the measured build alongside theirs; claiming the untested one was already
 * installed would quietly hand them a configuration nothing here was verified against.</p>
 */
public final class ShaderPackLibrary {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** What the action button offers for a pack right now. */
    public enum State {
        /** Not on disk — one click downloads it and turns it on. */
        MISSING,
        /** Already in {@code shaderpacks/} — one click turns it on. */
        INSTALLED,
        /** The pack Iris is configured to use. Nothing to do. */
        ACTIVE,
        /** Fetching. The button shows the progress bar instead. */
        DOWNLOADING,
        /** The last download failed; the page shows why, and offers the pack's own page. */
        FAILED
    }

    /**
     * How long a directory listing stands before it is re-read.
     *
     * <p>The page asks "is this installed" for every row on every frame, and the answer only changes
     * when a download finishes or the player drops a zip in behind the game's back. A short TTL
     * keeps both true without putting ten stat calls into every frame.</p>
     */
    private static final long LISTING_TTL_MS = 500L;

    private static Set<String> listing = Set.of();
    private static long listedAt = 0L;

    private ShaderPackLibrary() {}

    /** Forget the cached listing — called the moment a download lands, so the page updates at once. */
    public static void invalidate() {
        listedAt = 0L;
    }

    private static Set<String> installedFiles() {
        long now = System.currentTimeMillis();
        if (now - listedAt < LISTING_TTL_MS) {
            return listing;
        }
        Set<String> found = new HashSet<>();
        try {
            Path dir = directory();
            if (Files.isDirectory(dir)) {
                try (Stream<Path> stream = Files.list(dir)) {
                    stream.filter(Files::isRegularFile)
                            .forEach(p -> found.add(p.getFileName().toString().toLowerCase(Locale.ROOT)));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Could not list shaderpacks/: {}", e.toString());
        }
        listing = found;
        listedAt = now;
        return listing;
    }

    /** {@code <gameDir>/shaderpacks} — Iris' own directory, created on demand. */
    public static Path directory() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("shaderpacks");
    }

    public static boolean installed(ShaderPack pack) {
        return installedFiles().contains(pack.filename().toLowerCase(Locale.ROOT));
    }

    /** True when {@code config/iris.properties} names this pack and shaders are on. */
    public static boolean active(ShaderPack pack) {
        return pack.filename().equals(IrisPackControl.currentPackName());
    }

    /** True when shaders are switched off entirely — the "Shaders off" row's selected state. */
    public static boolean shadersOff() {
        return IrisPackControl.currentPackName().isEmpty();
    }

    public static State stateOf(ShaderPack pack) {
        if (ShaderPackDownloader.isDownloading(pack)) {
            return State.DOWNLOADING;
        }
        if (active(pack)) {
            return State.ACTIVE;
        }
        if (installed(pack)) {
            return State.INSTALLED;
        }
        return ShaderPackDownloader.errorFor(pack) != null ? State.FAILED : State.MISSING;
    }
}
