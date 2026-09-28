package games.brennan.dungeontrain.client.analytics;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Assembly tests for {@link UiAnalytics#buildPayload}. The relay validates this against
 * dp-relay {@code ui-events.js} (enum whitelists; {@code durationMs} only on {@code page_time}).
 * Pure — no running server or Minecraft bootstrap needed.
 */
class UiAnalyticsPayloadTest {

    private static final String UUID = "069a79f444e94726a5befca90e38aaf5";

    @Test
    @DisplayName("click payload carries uuid, player, modVersion and the three enums")
    void clickPayload() {
        JsonObject out = UiAnalytics.buildPayload(
                UUID, "NyoomBomb", "0.497.0",
                UiAnalytics.SURFACE_SUPPORT_PAGE, UiAnalytics.TARGET_DONATE, "click", -1);
        assertEquals(UUID, out.get("uuid").getAsString());
        assertEquals("NyoomBomb", out.get("player").getAsString());
        assertEquals("0.497.0", out.get("modVersion").getAsString());
        assertEquals("support_page", out.get("surface").getAsString());
        assertEquals("donate", out.get("target").getAsString());
        assertEquals("click", out.get("action").getAsString());
        assertFalse(out.has("durationMs"));
    }

    @Test
    @DisplayName("page_time payload carries durationMs")
    void pageTimePayload() {
        JsonObject out = UiAnalytics.buildPayload(
                UUID, "NyoomBomb", "0.497.0",
                UiAnalytics.SURFACE_SUPPORT_PAGE, UiAnalytics.TARGET_PAGE, "page_time", 20500L);
        assertEquals("page_time", out.get("action").getAsString());
        assertEquals(20500L, out.get("durationMs").getAsLong());
    }

    @Test
    @DisplayName("zero duration is a valid page_time; negative means omit")
    void durationBoundary() {
        assertEquals(0L, UiAnalytics.buildPayload(UUID, "x", "v",
                UiAnalytics.SURFACE_SUPPORT_PAGE, UiAnalytics.TARGET_PAGE, "page_time", 0L)
                .get("durationMs").getAsLong());
        assertFalse(UiAnalytics.buildPayload(UUID, "x", "v",
                UiAnalytics.SURFACE_TITLE_SCREEN, UiAnalytics.TARGET_SUPPORT, "click", -1)
                .has("durationMs"));
    }

    @Test
    @DisplayName("null or blank player/modVersion are omitted")
    void optionalFields() {
        JsonObject out = UiAnalytics.buildPayload(UUID, null, "",
                UiAnalytics.SURFACE_TITLE_SCREEN, UiAnalytics.TARGET_DISCORD, "confirm_yes", -1);
        assertFalse(out.has("player"));
        assertFalse(out.has("modVersion"));
        assertEquals("confirm_yes", out.get("action").getAsString());
    }

    @Test
    @DisplayName("death-screen page open carries the page dimension, no survey fields")
    void deathPageOpen() {
        JsonObject out = UiAnalytics.buildPayload(UUID, "NyoomBomb", "0.512.0",
                UiAnalytics.SURFACE_DEATH_SCREEN, UiAnalytics.TARGET_PAGE, "open", -1,
                UiAnalytics.PAGE_DONATE, null, -1, -1);
        assertEquals("death_screen", out.get("surface").getAsString());
        assertEquals("donate", out.get("page").getAsString());
        assertFalse(out.has("durationMs"));
        assertFalse(out.has("questionId"));
        assertFalse(out.has("score"));
    }

    @Test
    @DisplayName("death-screen page_time carries both page and durationMs")
    void deathPageTime() {
        JsonObject out = UiAnalytics.buildPayload(UUID, "x", "v",
                UiAnalytics.SURFACE_DEATH_SCREEN, UiAnalytics.TARGET_PAGE, "page_time", 4200L,
                UiAnalytics.PAGE_GEAR, null, -1, -1);
        assertEquals("gear", out.get("page").getAsString());
        assertEquals(4200L, out.get("durationMs").getAsLong());
    }

    @Test
    @DisplayName("survey page open carries page=survey + questionId, no score")
    void surveyPageOpen() {
        JsonObject out = UiAnalytics.buildPayload(UUID, "x", "v",
                UiAnalytics.SURFACE_DEATH_SCREEN, UiAnalytics.TARGET_PAGE, "open", -1,
                UiAnalytics.PAGE_SURVEY, "nps", -1, -1);
        assertEquals("survey", out.get("page").getAsString());
        assertEquals("nps", out.get("questionId").getAsString());
        assertFalse(out.has("score"));
        assertFalse(out.has("durationMs"));
    }

    @Test
    @DisplayName("survey_answer carries questionId, score, scoreMax (never a comment)")
    void surveyAnswer() {
        JsonObject out = UiAnalytics.buildPayload(UUID, "x", "v",
                UiAnalytics.SURFACE_DEATH_SCREEN, UiAnalytics.TARGET_PAGE, "survey_answer", -1,
                UiAnalytics.PAGE_SURVEY, "nps", 9, 10);
        assertEquals("survey_answer", out.get("action").getAsString());
        assertEquals("nps", out.get("questionId").getAsString());
        assertEquals(9, out.get("score").getAsInt());
        assertEquals(10, out.get("scoreMax").getAsInt());
        assertFalse(out.has("comment"));
    }

    @Test
    @DisplayName("a zero survey score is kept; blank page/questionId are omitted")
    void surveyScoreZeroAndBlanks() {
        JsonObject out = UiAnalytics.buildPayload(UUID, "x", "v",
                UiAnalytics.SURFACE_DEATH_SCREEN, UiAnalytics.TARGET_PAGE, "survey_answer", -1,
                "  ", "  ", 0, 5);
        assertEquals(0, out.get("score").getAsInt());
        assertFalse(out.has("page"));
        assertFalse(out.has("questionId"));
    }

    @Test
    @DisplayName("death-screen button click: page and survey fields all omitted")
    void buttonClick() {
        JsonObject out = UiAnalytics.buildPayload(UUID, "x", "v",
                UiAnalytics.SURFACE_DEATH_SCREEN, UiAnalytics.TARGET_CONTRIBUTE, "click", -1,
                null, null, -1, -1);
        assertEquals("contribute", out.get("target").getAsString());
        assertFalse(out.has("page"));
        assertFalse(out.has("scoreMax"));
    }
    @Test
    @DisplayName("the A/B arm rides on any event, alongside its experiment")
    void abDimensions() {
        JsonObject out = UiAnalytics.buildPayload(UUID, "x", "v",
                UiAnalytics.SURFACE_DEATH_SCREEN, UiAnalytics.TARGET_PAGE, "open", -1,
                UiAnalytics.PAGE_DONATE, null, -1, -1, "donate_cards_v1", "c_hours_active", null);
        assertEquals("donate_cards_v1", out.get("exp").getAsString());
        assertEquals("c_hours_active", out.get("variant").getAsString());
    }

    @Test
    @DisplayName("no experiment running: neither field is sent")
    void abAbsentByDefault() {
        // The state of every jar until an experiment starts, and of every player again once one
        // ends. The relay reads an event with no `exp` as belonging to no arm.
        JsonObject out = UiAnalytics.buildPayload(UUID, "x", "v",
                UiAnalytics.SURFACE_DEATH_SCREEN, UiAnalytics.TARGET_PAGE, "open", -1,
                UiAnalytics.PAGE_DONATE, null, -1, -1, null, null, null);
        assertFalse(out.has("exp"));
        assertFalse(out.has("variant"));
    }

    @Test
    @DisplayName("an arm without its experiment is never sent alone")
    void variantNeedsItsExperiment() {
        // Two experiments could each name an arm `control`, so a bare variant cannot be attributed
        // to either — the relay drops it, and this never sends it in the first place.
        JsonObject out = UiAnalytics.buildPayload(UUID, "x", "v",
                UiAnalytics.SURFACE_DEATH_SCREEN, UiAnalytics.TARGET_PAGE, "open", -1,
                UiAnalytics.PAGE_DONATE, null, -1, -1, "  ", "c_hours_active", null);
        assertFalse(out.has("exp"));
        assertFalse(out.has("variant"));
    }
    @Test
    @DisplayName("the rotating arm also reports which pair was on screen")
    void rotatingArmCarriesThePair() {
        JsonObject out = UiAnalytics.buildPayload(UUID, "x", "v",
                UiAnalytics.SURFACE_DEATH_SCREEN, UiAnalytics.TARGET_PAGE, "open", -1,
                UiAnalytics.PAGE_DONATE, null, -1, -1,
                "donate_cards_v1", "f_rotating", "c_hours_active");
        assertEquals("f_rotating", out.get("variant").getAsString(),
                "the ARM stays the treatment being compared");
        assertEquals("c_hours_active", out.get("pair").getAsString(),
                "the pair is a separate dimension, never folded into the arm");
    }

    @Test
    @DisplayName("a pair without its arm is dropped — it would belong to no treatment")
    void pairNeedsItsArm() {
        JsonObject out = UiAnalytics.buildPayload(UUID, "x", "v",
                UiAnalytics.SURFACE_DEATH_SCREEN, UiAnalytics.TARGET_PAGE, "open", -1,
                UiAnalytics.PAGE_DONATE, null, -1, -1, "donate_cards_v1", null, "c_hours_active");
        assertFalse(out.has("pair"));
    }
}
