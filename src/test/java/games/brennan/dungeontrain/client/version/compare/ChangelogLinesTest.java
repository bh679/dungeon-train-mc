package games.brennan.dungeontrain.client.version.compare;

import games.brennan.dungeontrain.client.shaders.ShaderDetailPane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChangelogLinesTest {

    private static final String NOTES = """
            ### 0.849.0

            **Edit your own line on the Credits page**

            If you're credited, an Edit button now sits beside your name. See [the wiki](https://x.y).

            - Edit beside your own row
            - Rename yourself — `one name` across every card
            """;

    private static List<String> texts(List<ShaderDetailPane.Line> lines) {
        return lines.stream().map(l -> l.text().getString()).toList();
    }

    @Test
    @DisplayName("heading, bold title, plain paragraph, bullets — markers stripped, nothing dropped")
    void classify() {
        List<ShaderDetailPane.Line> lines = ChangelogLines.fromMarkdown(NOTES);
        assertEquals(List.of(
                "Edit your own line on the Credits page",
                "If you're credited, an Edit button now sits beside your name. See the wiki.",
                "• Edit beside your own row",
                "• Rename yourself — one name across every card"), texts(lines));
        assertEquals(ChangelogLines.COLOUR_TITLE, lines.get(0).colour());
        assertEquals(ChangelogLines.COLOUR_BODY, lines.get(1).colour());
        assertEquals(ChangelogLines.COLOUR_BULLET, lines.get(2).colour());
    }

    @Test
    @DisplayName("each entry opens with its own version heading; the notes' own version line is folded into it")
    void entries() {
        ReleaseEntry a = new ReleaseEntry(FullSemver.parse("0.849.0").orElseThrow(), NOTES, "");
        ReleaseEntry b = new ReleaseEntry(FullSemver.parse("0.843.0").orElseThrow(), "### 0.843.0\n\nJust text", "");
        List<ShaderDetailPane.Line> lines = ChangelogLines.forEntries(List.of(a, b));
        assertEquals("v0.849.0", lines.get(0).text().getString());
        assertEquals(ChangelogLines.COLOUR_HEADING, lines.get(0).colour());
        assertEquals("v0.843.0", lines.get(5).text().getString());
        assertEquals("Just text", lines.get(6).text().getString());
        assertEquals(7, lines.size());
    }

    @Test
    @DisplayName("a sub-heading that is not a version stays a heading")
    void subHeading() {
        List<ShaderDetailPane.Line> lines = ChangelogLines.fromMarkdown("## Fixes\n- one");
        assertEquals("Fixes", lines.get(0).text().getString());
        assertEquals(ChangelogLines.COLOUR_HEADING, lines.get(0).colour());
    }
}
