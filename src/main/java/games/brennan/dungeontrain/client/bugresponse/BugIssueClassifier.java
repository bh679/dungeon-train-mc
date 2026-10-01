package games.brennan.dungeontrain.client.bugresponse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Works out which {@link BugIssue} a death-screen bug report is about. The picked option decides it
 * ("Lag", "Train Vanished"); an "Other" report whose comment mentions the train going missing,
 * derailing or duplicating counts as {@link BugIssue#TRAIN_VANISHED} too. The words come from
 * {@code assets/dungeontrain/bug_response/keywords.json} so the list can grow without a code change.
 * Pure apart from reading that bundled resource once.
 */
public final class BugIssueClassifier {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String KEYWORDS_RESOURCE = "/assets/dungeontrain/bug_response/keywords.json";

    private static List<String> trainKeywords;

    private BugIssueClassifier() {}

    /** Classify with the bundled keyword list. */
    public static BugIssue classify(String optionLabel, String comment) {
        return classify(optionLabel, comment, trainKeywords());
    }

    static BugIssue classify(String optionLabel, String comment, List<String> trainKeywords) {
        String option = optionLabel == null ? "" : optionLabel.strip();
        if (option.equalsIgnoreCase("Lag")) return BugIssue.LAG;
        if (option.equalsIgnoreCase("Train Vanished")) return BugIssue.TRAIN_VANISHED;
        if (comment != null && !comment.isBlank()) {
            String text = comment.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            for (String word : trainKeywords) {
                if (text.contains(word)) return BugIssue.TRAIN_VANISHED;
            }
        }
        return BugIssue.OTHER;
    }

    static synchronized List<String> trainKeywords() {
        if (trainKeywords == null) {
            trainKeywords = loadKeywords();
        }
        return trainKeywords;
    }

    static List<String> parseKeywords(String json) {
        List<String> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) return out;
        JsonElement arr = ((JsonObject) root).get("train_vanished");
        if (arr == null || !arr.isJsonArray()) return out;
        for (JsonElement el : (JsonArray) arr) {
            if (el.isJsonPrimitive()) {
                String word = el.getAsString().strip().toLowerCase(Locale.ROOT);
                if (!word.isEmpty()) out.add(word);
            }
        }
        return List.copyOf(out);
    }

    private static List<String> loadKeywords() {
        try (InputStream in = BugIssueClassifier.class.getResourceAsStream(KEYWORDS_RESOURCE)) {
            if (in == null) {
                LOGGER.warn("[DungeonTrain] Bug-report keyword list {} missing", KEYWORDS_RESOURCE);
                return List.of();
            }
            return parseKeywords(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Bug-report keyword list unreadable: {}", e.toString());
            return List.of();
        }
    }
}
