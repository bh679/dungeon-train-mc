package games.brennan.dungeontrain.client.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Short display labels for survey answers on the title-screen chat panel: maps a survey's question
 * prompt (which arrives as the {@code 📋 Feedback — …} embed's description) to a compact label, so an
 * answer renders as "Bug — Other" or "Recommend 9/10" instead of the full question sentence.
 *
 * <p>The prompts are read from Dungeon Train's own bundled survey definitions
 * ({@code data/dungeontrain/dp_surveys/<id>.json} — the same files DiscordPresence serves to the
 * death-screen survey), so the mapping can't drift from what the survey actually asks. The label per
 * survey id lives in {@link #LABELS}; a survey id without a label (or a prompt that no longer matches,
 * e.g. an answer posted by an older mod version) simply renders unlabeled — fail-soft, never wrong.</p>
 */
final class SurveyLabels {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** survey id (the {@code dp_surveys} filename) → compact panel label. */
    private static final Map<String, String> LABELS = Map.of(
            "bug_report", "Bug",
            "change_one_thing", "Change one thing");

    private static final String RESOURCE_DIR = "/data/dungeontrain/dp_surveys/";

    /** prompt text (trimmed) → label; built once from the bundled survey JSONs. */
    private static volatile Map<String, String> byPrompt;

    private SurveyLabels() {}

    /** The compact label for a survey question prompt, or null when unknown (render unlabeled). */
    static String labelFor(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        return promptMap().get(prompt.strip());
    }

    private static Map<String, String> promptMap() {
        Map<String, String> map = byPrompt;
        if (map == null) {
            map = new HashMap<>();
            for (Map.Entry<String, String> e : LABELS.entrySet()) {
                String prompt = readPrompt(e.getKey());
                if (prompt != null) {
                    map.put(prompt, e.getValue());
                }
            }
            byPrompt = map;
        }
        return map;
    }

    /** The {@code prompt} of a bundled survey definition, or null when missing/unreadable. */
    private static String readPrompt(String surveyId) {
        try (InputStream in = SurveyLabels.class.getResourceAsStream(RESOURCE_DIR + surveyId + ".json")) {
            if (in == null) {
                return null;
            }
            JsonObject o = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return o.has("prompt") && o.get("prompt").isJsonPrimitive()
                    ? o.get("prompt").getAsString().strip() : null;
        } catch (Exception e) {
            LOGGER.debug("Menu chat: could not read survey definition {}: {}", surveyId, e.toString());
            return null;
        }
    }
}
