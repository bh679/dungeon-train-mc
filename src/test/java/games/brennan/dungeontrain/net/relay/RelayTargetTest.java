package games.brennan.dungeontrain.net.relay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * How the admin relay URL is read off disk.
 *
 * <p>The three-place chain itself is not testable here — it reads {@code FMLPaths} and the
 * environment — so what is pinned is the seam the chain is built out of, and the one distinction the
 * chain depends on: {@code null} means "nothing here, try the next place", {@code ""} means "there
 * was something here and it was not a URL", which must STOP the search. Collapse those two and a
 * typo in a checkout's own file is masked by the machine-wide value and becomes undiagnosable —
 * which is the failure this whole lookup was rewritten to avoid.</p>
 */
final class RelayTargetTest {

    private static final String URL = "https://example.invalid/api/dp-relay/deadbeef";

    @Test
    @DisplayName("a URL line is read, and a trailing slash is dropped")
    void readsAUrl(@TempDir Path dir) throws IOException {
        assertEquals(URL, RelayTarget.readUrlFrom(write(dir, URL)));
        assertEquals(URL, RelayTarget.readUrlFrom(write(dir, URL + "/")));
        assertEquals(URL, RelayTarget.readUrlFrom(write(dir, "   " + URL + "  ")));
    }

    @Test
    @DisplayName("comments and blank lines are skipped, and the URL beneath them is found")
    void skipsComments(@TempDir Path dir) throws IOException {
        assertEquals(URL, RelayTarget.readUrlFrom(write(dir, "# paste it below\n\n" + URL)));
    }

    @Test
    @DisplayName("a file that is only comments says nothing, so the next place is tried")
    void placeholderIsNotAnAnswer(@TempDir Path dir) throws IOException {
        assertNull(RelayTarget.readUrlFrom(write(dir, "# nothing pasted yet\n\n")));
        assertNull(RelayTarget.readUrlFrom(write(dir, "")));
    }

    @Test
    @DisplayName("a missing file says nothing rather than failing")
    void missingFile(@TempDir Path dir) {
        assertNull(RelayTarget.readUrlFrom(dir.resolve("not-here.txt")));
    }

    @Test
    @DisplayName("a line that is not a URL refuses, and does NOT fall through to the next place")
    void junkStopsTheSearch(@TempDir Path dir) throws IOException {
        // The shape this actually took: a stray word typed onto the front of the comment line, which
        // stopped it being a comment and became the "URL".
        assertEquals("", RelayTarget.readUrlFrom(write(dir, "Keit# paste the URL below")));
        assertEquals("", RelayTarget.readUrlFrom(write(dir, "brennan.games/api/dp-relay/x")),
                "a bare host is not a URL — it would fail every call with a DNS error");
    }

    private static Path write(Path dir, String content) throws IOException {
        Path p = dir.resolve("relay-admin-url-" + Math.abs(content.hashCode()) + ".txt");
        Files.writeString(p, content);
        return p;
    }
}
