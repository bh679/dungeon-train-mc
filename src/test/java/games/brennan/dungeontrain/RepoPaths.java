package games.brennan.dungeontrain;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the repository root for tests that read the shipped resource tree.
 *
 * <h2>Why this exists</h2>
 * NeoForge's ModDevGradle runs the {@code test} task with its working directory set to
 * {@code build/minecraft-junit}, <em>not</em> the project directory. So a test that says
 * {@code Path.of("src/main/resources/…")} resolves to a path that does not exist.
 *
 * <p>That is a silent failure, and it had already bitten: the guard asserting every localized Death
 * Note title stays typeable was written with a {@code if (!Files.isDirectory(…)) return;} escape
 * hatch for "running outside the repo tree", which meant it never actually ran — it passed by
 * skipping, every time, for as long as it had existed. The same shape hid a build that shipped with
 * no English Love Note strings at all.</p>
 *
 * <p>So this class walks up from the working directory to find the root, and {@link #root()}
 * <b>throws</b> when it cannot. A resource test that cannot find its resources must fail loudly
 * rather than quietly report success.</p>
 */
public final class RepoPaths {

    /** Files that together identify the repo root and no other directory on the way up. */
    private static final String[] MARKERS = {"gradle.properties", "settings.gradle"};
    /** Safety bound on the upward walk. */
    private static final int MAX_DEPTH = 12;

    private RepoPaths() {}

    /**
     * The repository root, found by walking up from the JVM's working directory.
     *
     * @throws IllegalStateException if no ancestor carries the marker files — never a silent skip
     */
    public static Path root() {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int i = 0; i <= MAX_DEPTH && dir != null; i++, dir = dir.getParent()) {
            if (isRoot(dir)) return dir;
        }
        throw new IllegalStateException(
            "Could not locate the repository root walking up from user.dir="
                + System.getProperty("user.dir") + " (looked for " + String.join(" + ", MARKERS)
                + " alongside src/main/resources). Resource-backed tests cannot run.");
    }

    private static boolean isRoot(Path dir) {
        for (String marker : MARKERS) {
            if (!Files.isRegularFile(dir.resolve(marker))) return false;
        }
        return Files.isDirectory(dir.resolve("src/main/resources"));
    }

    /** {@code src/main/resources} — the shipped resource tree. */
    public static Path resources() {
        return root().resolve("src/main/resources");
    }

    /** A locale's GUI lang file, e.g. {@code en_us}. */
    public static Path langFile(String locale) {
        return resources().resolve("assets/dungeontrain/lang/" + locale + ".json");
    }

    /** The mod's advancement definitions. */
    public static Path advancements() {
        return resources().resolve("data/dungeontrain/advancement");
    }

    /** The per-locale narrative overlays ({@code narrative_localizations/<locale>/…}). */
    public static Path narrativeLocalizations() {
        return resources().resolve("data/dungeontrain/narrative_localizations");
    }
}
