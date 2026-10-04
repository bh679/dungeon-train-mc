package games.brennan.dungeontrain.discord;

import games.brennan.discordpresence.discord.DeathField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The fields of the build-submitted announcement. */
class BuildSubmitReporterTest {

    @Test
    @DisplayName("the version line leads with the freshness dot")
    void versionLine() {
        assertEquals("🟢 DT 0.1149.0", BuildSubmitReporter.versionLine(VersionFreshness.LATEST, "0.1149.0"));
        assertEquals("🔴 DT 0.1001.0", BuildSubmitReporter.versionLine(VersionFreshness.OLDER, " 0.1001.0 "));
    }

    @Test
    @DisplayName("an unknown version gives no line")
    void unknownVersion() {
        assertEquals("", BuildSubmitReporter.versionLine(VersionFreshness.UNKNOWN, null));
        assertEquals("", BuildSubmitReporter.versionLine(VersionFreshness.UNKNOWN, " "));
    }

    @Test
    @DisplayName("id, kind and version share one bold-labelled line")
    void buildLineWithVersion() {
        assertEquals("**Build** #45991 · building · 🟢 DT 0.1149.0",
                BuildSubmitReporter.buildLine(45991, "building", null, "🟢 DT 0.1149.0"));
    }

    @Test
    @DisplayName("no version line leaves it off the line")
    void buildLineWithoutVersion() {
        assertEquals("**Build** #155 · carriage / roof",
                BuildSubmitReporter.buildLine(155, "carriage", "roof", ""));
    }

    @Test
    @DisplayName("the review-verdict field is unchanged")
    void reviewField() {
        assertEquals(List.of(new DeathField("Build", "#155 · carriage / roof")),
                BuildSubmitReporter.fields(155, "carriage", "roof"));
    }
}
