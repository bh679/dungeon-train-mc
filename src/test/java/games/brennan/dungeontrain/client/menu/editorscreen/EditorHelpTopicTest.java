package games.brennan.dungeontrain.client.menu.editorscreen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every Help topic's title and paragraphs must exist in {@code en_us.json}, and no paragraph past
 * the declared count may — a stray {@code .p6} would never be shown.
 */
final class EditorHelpTopicTest {

    @Test
    void everyTopicIsWrittenInEnglish() throws IOException {
        Path enUs = RepoPaths.langFile("en_us");
        assertTrue(Files.isRegularFile(enUs), "missing " + enUs);
        JsonObject en = JsonParser.parseString(Files.readString(enUs, StandardCharsets.UTF_8)).getAsJsonObject();
        List<String> problems = new ArrayList<>();
        for (EditorHelpTopic topic : EditorHelpTopic.values()) {
            if (!en.has(topic.titleKey())) problems.add("missing " + topic.titleKey());
            List<String> keys = topic.paragraphKeys();
            assertTrue(!keys.isEmpty(), topic + " has no paragraphs");
            for (String key : keys) {
                if (!en.has(key)) problems.add("missing " + key);
                else if (en.get(key).getAsString().isBlank()) problems.add("blank " + key);
            }
            String orphan = EditorScreenLang.HELP_PREFIX + topic.id() + ".p" + (keys.size() + 1);
            if (en.has(orphan)) problems.add("never shown: " + orphan);
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
