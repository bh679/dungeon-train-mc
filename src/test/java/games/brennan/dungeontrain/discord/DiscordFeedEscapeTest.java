package games.brennan.dungeontrain.discord;

import games.brennan.discordpresence.discord.DeathField;
import games.brennan.dungeontrain.builder.relay.SubmitNote;
import games.brennan.dungeontrain.logrequest.DeliveryFailureReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every Discord feed escapes the player text it weaves in, so a name like {@code ashy_} reads literally
 * instead of pairing its underscore with a later one and underlining the rest of the post. Intended
 * markdown (bold labels, the epitaph's italics) is left as markdown.
 */
final class DiscordFeedEscapeTest {

    private static final String NAME = "ashy_";
    private static final String ESCAPED = "ashy\\_";

    private static String value(List<DeathField> fields, String name) {
        for (DeathField f : fields) {
            if (f.name().equals(name)) return f.value();
        }
        return null;
    }

    @Test
    @DisplayName("death manifest: title, cause and advancement titles are escaped")
    void deathManifest() {
        assertEquals(ESCAPED + " - Carriage 47", DeathManifestFormat.title(NAME, 47));
        List<DeathField> fields = DeathManifestFormat.fields("You were slain by " + NAME,
                1284.0, 15160L, 412.0, 196.0, 21, 4, List.of("the_long_run"), 9, 2, 5, 0);
        assertTrue(value(fields, "The fall").contains("They were slain by " + ESCAPED), value(fields, "The fall"));
        assertTrue(value(fields, "Advancements").endsWith("the\\_long\\_run"), value(fields, "Advancements"));
    }

    @Test
    @DisplayName("tamed animals: each name is escaped")
    void tamed() {
        assertEquals("2 (glow\\_squid, Horse)", DeathReportFormat.tamed(2, List.of("glow_squid", "Horse")));
    }

    @Test
    @DisplayName("milestones: player and advancement title are escaped")
    void milestones() {
        assertEquals("🏆 " + ESCAPED + " earned the\\_long\\_run", MilestoneAdvancementReporter.title(NAME, "the_long_run"));
        assertEquals("🥇 " + ESCAPED + " — first passenger ever to earn Cross-Country",
                MilestoneAdvancementReporter.firstEverTitle(NAME, "Cross-Country"));
    }

    @Test
    @DisplayName("tributes: names escaped, and an own photo is still recognised")
    void tributes() {
        assertEquals("📸 " + ESCAPED + " paid tribute to a photo by \\*Alex\\*", TributePhotoReporter.title(NAME, "*Alex*"));
        assertEquals("📸 " + ESCAPED + " paid tribute to their own photo", TributePhotoReporter.title(NAME, NAME));
        assertEquals("📸 " + ESCAPED + " tributed their photo. Was it worth it?", TributePhotoReporter.ownTitle(NAME));
    }

    @Test
    @DisplayName("PlayerMob photo: photographer and subject are escaped")
    void playerMobPhoto() {
        assertEquals("📸 Shutter\\_bug took a photo of " + ESCAPED, PlayerMobPhotoReporter.title("Shutter_bug", NAME));
        assertEquals(ESCAPED + " handed over a camera · Shutter\\_bug handed back the print",
                PlayerMobPhotoReporter.description("Shutter_bug", NAME));
    }

    @Test
    @DisplayName("build submit: names and answers escaped, bold labels kept")
    void buildSubmit() {
        assertEquals(ESCAPED + " submitted brick\\_cabin for review", BuildSubmitReporter.title(NAME, "brick_cabin"));
        String d = BuildSubmitReporter.description(new SubmitNote("lever_door", "", "**loud**"));
        assertEquals("**Redstone:** lever\\_door\n**Notes:** \\*\\*loud\\*\\*", d);
    }

    @Test
    @DisplayName("build submit: a long answer is clipped before escaping, never leaving a lone backslash")
    void buildSubmitClipThenEscape() {
        String answer = "a".repeat(BuildSubmitReporter.ANSWER_MAX - 1) + "_tail";
        String d = BuildSubmitReporter.description(new SubmitNote("", "", answer));
        assertEquals("**Notes:** " + "a".repeat(BuildSubmitReporter.ANSWER_MAX - 1) + "\\_…", d);
    }

    @Test
    @DisplayName("build review: reviewer, build, owner and comment are escaped")
    void buildReview() {
        assertEquals("Bren\\_ accepted brick\\_cabin by " + ESCAPED,
                BuildReviewReporter.title("Bren_", "brick_cabin", NAME));
        assertEquals("needs a \\_door\\_", BuildReviewReporter.description("needs a _door_"));
        assertFalse(BuildReviewReporter.description(null).contains("\\"));
    }

    @Test
    @DisplayName("delivery failure: the player's name is escaped")
    void deliveryFailure() {
        assertTrue(DeliveryFailureReport.line(NAME, DeliveryFailureReport.Reason.TOO_SOON)
                .contains("(" + ESCAPED + ")"));
    }
}
