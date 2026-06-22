package games.brennan.dungeontrain.discord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Formatting + truncation tests for {@link WorldJoinReport#buildSuffix}. The join-message world-info
 * block must carry the version banner + train-regen line, collapse the mod list behind a Discord
 * spoiler ({@code ||…||}), and stay under Discord's 2000-char message cap even for a huge modpack.
 */
class WorldJoinReportTest {

    /** Mirrors {@code WorldJoinReport.MAX_SUFFIX_CHARS} — the hard ceiling the suffix must respect. */
    private static final int MAX_SUFFIX_CHARS = 1800;

    @Test
    @DisplayName("short mod list: full list on one line inside a spoiler, no truncation note")
    void shortListFitsWholeInsideSpoiler() {
        String out = WorldJoinReport.buildSuffix(
                "1.2.3", "**Train seed:** `42`", List.of("alpha v1.0", "beta v2.0"));

        assertTrue(out.contains("Dungeon Train v1.2.3"), out);
        assertTrue(out.contains("**Train seed:** `42`"), out);
        assertTrue(out.contains("||alpha v1.0, beta v2.0||"), out);
        assertFalse(out.indexOf('\n', out.indexOf("||")) >= 0, "mods must be on a single line: " + out);
        assertFalse(out.contains("more"), "no truncation expected: " + out);
        assertTrue(out.length() <= MAX_SUFFIX_CHARS);
    }

    @Test
    @DisplayName("huge mod list: truncated with +N more, spoiler closed, under the cap")
    void hugeListTruncatesUnderCap() {
        List<String> mods = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            mods.add(String.format("some_long_mod_id_%03d v1.2.3", i));
        }

        String out = WorldJoinReport.buildSuffix("0.345.3", "**Train seed:** `-987654321`", mods);

        assertTrue(out.length() <= MAX_SUFFIX_CHARS, "suffix must fit the cap, was " + out.length());
        assertTrue(out.contains("more"), "expected a '+N more' truncation note");
        assertTrue(out.endsWith("||"), "spoiler must be closed");
        assertTrue(out.contains("**Mods (400):**"), "label should report the true total, not the shown count");
    }

    @Test
    @DisplayName("empty mod list: still well-formed (count 0, empty spoiler)")
    void emptyListWellFormed() {
        String out = WorldJoinReport.buildSuffix("1.0.0", "**Train seed:** `0`", List.of());

        assertTrue(out.contains("**Mods (0):** ||||"), out);
        assertFalse(out.contains("more"), out);
        assertTrue(out.length() <= MAX_SUFFIX_CHARS);
    }
}
