package games.brennan.dungeontrain.client.menu.editorscreen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every key constant in {@link EditorScreenLang} must exist in {@code en_us.json}. */
final class EditorScreenLangKeysTest {

    @Test
    void everyKeyExistsInEnglish() throws IOException, IllegalAccessException {
        Path enUs = RepoPaths.langFile("en_us");
        assertTrue(Files.isRegularFile(enUs), "missing " + enUs);
        JsonObject en = JsonParser.parseString(Files.readString(enUs, StandardCharsets.UTF_8)).getAsJsonObject();
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Field f : EditorScreenLang.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
            if (!Modifier.isPublic(f.getModifiers())) continue;
            String key = (String) f.get(null);
            checked++;
            if (!en.has(key)) missing.add(f.getName() + " → " + key);
        }
        assertTrue(checked > 40, "expected the key constants to be scanned, got " + checked);
        assertTrue(missing.isEmpty(), "missing from en_us.json: " + missing);
    }
}
