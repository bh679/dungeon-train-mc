package games.brennan.dungeontrain.track.variant;

import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the read/write agreement for track-kind content — portal rooms,
 * tunnels, track tiles and pillars.
 *
 * <h2>The bug this pins</h2>
 * These stores <i>read</i> through
 * {@code UserContentPaths.findFile(kind.subdir(), …)}, which prefers the active
 * package, but used to <i>write</i> to a hard-coded
 * {@code config/dungeontrain/user/<subdir>} via {@code TrackKind.configSubdir()}.
 * Once a player saved a package the two halves pointed at different folders: a
 * removed block variant was written to {@code user/} while gameplay kept
 * reading {@code dtpacks/<pack>/}, so the removal silently didn't stick and the
 * old variant appeared to come back. Every write target now resolves through
 * {@code UserContentPaths.activeSubDir(kind.subdir())}.
 *
 * <p>Source-level rather than path-level: resolving a real path needs
 * {@code FMLPaths.CONFIGDIR} and a Forge bootstrap. Reading the declaration
 * catches a reintroduction of the fixed {@code user/} root, which is the
 * mistake worth catching — the folders themselves are covered in-game.</p>
 */
final class TrackKindPackagePathTest {

    /** {@code {file, method}} — path accessors that must resolve against the active package. */
    private static final String[][] WRITE_TARGETS = {
        {"track/variant/TrackVariantStore.java", "directory"},
        {"track/variant/TrackVariantBlocks.java", "configPathFor"},
        {"track/variant/TrackVariantWeights.java", "configPath"},
        {"editor/TrackVariantGroupStore.java", "directory"},
        {"portal/PortalRoomCopiesVariant.java", "configPathFor"},
    };

    @Test
    @DisplayName("TrackKind no longer exposes a fixed user/ subdir slug")
    void noFixedUserSubdirAccessor() {
        List<String> names = new ArrayList<>();
        for (Method m : TrackKind.class.getDeclaredMethods()) names.add(m.getName());

        assertFalse(names.contains("configSubdir"),
            "configSubdir() hard-coded config/dungeontrain/user/ and was the trap that made "
                + "track-kind writes package-blind. Resolve kind.subdir() against the active "
                + "package instead.");
        assertTrue(names.contains("subdir"), "subdir() is the slug callers should use");
    }

    @Test
    @DisplayName("every track-kind write target resolves through the active package")
    void writeTargetsResolveAgainstActivePackage() throws IOException {
        for (String[] target : WRITE_TARGETS) {
            String body = methodBody(target[0], target[1]);
            String where = target[0] + "#" + target[1] + "()";

            assertFalse(body.contains("CONFIGDIR"),
                where + " resolves against FMLPaths.CONFIGDIR. That pins writes to "
                    + "config/dungeontrain/user/ and desyncs them from the package-aware read in "
                    + "UserContentPaths.findFile — edits stop sticking once a package is active. "
                    + "Use UserContentPaths.activeSubDir(kind.subdir()).");
            assertTrue(body.contains("activeSubDir"),
                where + " should resolve through UserContentPaths.activeSubDir");
        }
    }

    /**
     * The source text of {@code method}'s body, from its signature line to the
     * first line that closes at method indentation. Deliberately simple — these
     * are four-line path helpers, not control flow.
     */
    private static String methodBody(String relativeFile, String method) throws IOException {
        Path file = RepoPaths.root().resolve("src/main/java/games/brennan/dungeontrain")
            .resolve(relativeFile);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            // Signature line, not a javadoc {@link} or a call site.
            if (!lines.get(i).contains(" " + method + "(") || !lines.get(i).contains("Path ")) continue;
            StringBuilder body = new StringBuilder();
            for (int j = i; j < lines.size(); j++) {
                body.append(lines.get(j)).append('\n');
                if (lines.get(j).equals("    }")) return body.toString();
            }
        }
        return fail("Couldn't find a 'Path " + method + "(' declaration in " + file
            + " — the test's file/method table is stale.");
    }
}
