package games.brennan.dungeontrain.narrative;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.RepoPaths;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards that every {@code "translate"} key an advancement references actually exists in
 * {@code en_us.json}.
 *
 * <p>Written after shipping a Love Note build whose English strings were silently lost: a
 * {@code git checkout} of the lang directory reverted en_us, and the script that re-inserted the
 * keys skipped it as "the reference locale". Nothing caught it — the mod built, the tests passed,
 * the translations were all present, and the advancements would have rendered as raw
 * {@code advancements.dungeontrain.…} keys for every English player.</p>
 *
 * <p>en_us is the one locale nothing else validates: {@code check-provenance.py} aligns each
 * translation against it, and {@code validate-locale.py} compares locales to a reference, so a
 * hole in the reference itself is invisible to both.</p>
 */
class AdvancementLangKeysTest {

    private static final Path ADVANCEMENTS = RepoPaths.advancements();
    private static final Path EN_US = RepoPaths.langFile("en_us");

    /** {@code "translate": "some.key"} — the only way advancement JSON names a lang key. */
    private static final Pattern TRANSLATE = Pattern.compile("\"translate\"\\s*:\\s*\"([^\"]+)\"");

    @Test
    void everyAdvancementTranslateKeyExistsInEnglish() throws IOException {
        assertTrue(Files.isDirectory(ADVANCEMENTS), "missing " + ADVANCEMENTS);
        assertTrue(Files.isRegularFile(EN_US), "missing " + EN_US);
        JsonObject en = JsonParser.parseString(Files.readString(EN_US, StandardCharsets.UTF_8))
                .getAsJsonObject();

        List<String> missing = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> tree = Files.walk(ADVANCEMENTS)) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".json")).toList()) {
                Matcher m = TRANSLATE.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    checked++;
                    String key = m.group(1);
                    if (!en.has(key)) missing.add(file.getFileName() + " → " + key);
                }
            }
        }
        assertTrue(checked > 0, "found no advancement translate keys at all — is the path right?");
        assertTrue(missing.isEmpty(),
                "advancement lang key(s) missing from en_us.json (English players would see the raw "
                        + "key): " + missing);
    }

    /**
     * The Love Note's own keys, spelled out. The sweep above only sees keys an advancement file
     * references, so it cannot catch a missing chat line — and the chat lines are exactly what went
     * missing the first time.
     */
    @Test
    void loveNoteChatAndAdvancementKeysAreAllPresentInEnglish() throws IOException {
        assertTrue(Files.isRegularFile(EN_US), "missing " + EN_US);
        JsonObject en = JsonParser.parseString(Files.readString(EN_US, StandardCharsets.UTF_8))
                .getAsJsonObject();
        String p = "advancements.dungeontrain.dungeon_train.";
        List<String> required = List.of(
                "chat.dungeontrain.lovenote.no_name",
                "chat.dungeontrain.lovenote.taken",
                p + "submitted_love_note.title", p + "submitted_love_note.description",
                p + "submitted_love_note.hint",
                p + "lovenote_self_target.title", p + "lovenote_self_target.description",
                p + "lovenote_self_target.hint",
                p + "no_good_deed.title", p + "no_good_deed.description", p + "no_good_deed.hint",
                p + "loved_back.title", p + "loved_back.description", p + "loved_back.hint");
        List<String> missing = required.stream().filter(k -> !en.has(k)).toList();
        assertTrue(missing.isEmpty(), "Love Note lang key(s) missing from en_us.json: " + missing);
    }

    /**
     * The two code-granted capstones, spelled out — including their {@code .hint} keys, which no
     * advancement JSON references (the hint system derives them by convention in
     * {@code AdvancementHintText}), so the sweep above cannot see them. "It's Not That Simple"
     * leans on its hint harder than any other advancement: the hint IS the command you have to
     * run, so a missing key doesn't just look wrong, it makes the advancement unearnable in
     * practice.
     */
    @Test
    void capstoneAdvancementKeysAreAllPresentInEnglish() throws IOException {
        assertTrue(Files.isRegularFile(EN_US), "missing " + EN_US);
        JsonObject en = JsonParser.parseString(Files.readString(EN_US, StandardCharsets.UTF_8))
                .getAsJsonObject();
        String p = "advancements.dungeontrain.dungeon_train.";
        List<String> required = List.of(
                p + "completionist.title", p + "completionist.description", p + "completionist.hint",
                p + "start_again.title", p + "start_again.description", p + "start_again.hint");
        List<String> missing = required.stream().filter(k -> !en.has(k)).toList();
        assertTrue(missing.isEmpty(), "capstone lang key(s) missing from en_us.json: " + missing);
        for (String key : required) {
            assertFalse(en.get(key).getAsString().isBlank(), key + " is blank in en_us.json");
        }
    }

    /** A key whose value is blank renders as nothing at all — as bad as a missing one. */
    @Test
    void noLoveNoteEnglishStringIsBlank() throws IOException {
        assertTrue(Files.isRegularFile(EN_US), "missing " + EN_US);
        JsonObject en = JsonParser.parseString(Files.readString(EN_US, StandardCharsets.UTF_8))
                .getAsJsonObject();
        for (String key : en.keySet()) {
            if (!key.contains("lovenote") && !key.contains("love_note") && !key.contains("loved_back")) {
                continue;
            }
            assertFalse(en.get(key).getAsString().isBlank(), key + " is blank in en_us.json");
        }
    }
}
