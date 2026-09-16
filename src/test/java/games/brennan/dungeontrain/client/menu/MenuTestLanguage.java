package games.brennan.dungeontrain.client.menu;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import games.brennan.dungeontrain.client.localization.edit.OverlayLanguage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.locale.Language;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Installs the shipped {@code en_us.json} as the active {@link Language} for one test class, so a
 * menu test can assert on the English a player reads now that {@link MenuLang} resolves every
 * label through it. Applied with {@code @ExtendWith(MenuTestLanguage.class)} on every test class
 * under {@code client/menu} — a global {@code META-INF/services} registration was tried first and
 * ran in a different class loader from the tests under the dev classpath's union filesystem, so
 * its {@link Language} was not theirs.
 *
 * <p>Restored after the class: Gradle runs many classes in one JVM, and the narrative tests assert
 * on raw keys, which an overlay left behind would translate out from under them.</p>
 */
public final class MenuTestLanguage implements BeforeAllCallback, AfterAllCallback {

    private static final Path EN_US = Path.of("src/main/resources/assets/dungeontrain/lang/en_us.json");
    private static Map<String, String> entries;

    private Language previous;

    @Override
    public void beforeAll(ExtensionContext context) {
        previous = Language.getInstance();
        Language.inject(new OverlayLanguage(previous, english()));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (previous != null) {
            Language.inject(previous);
        }
    }

    private static synchronized Map<String, String> english() {
        if (entries == null) {
            try {
                JsonObject json = JsonParser.parseString(
                    Files.readString(RepoPaths.root().resolve(EN_US), StandardCharsets.UTF_8)).getAsJsonObject();
                Map<String, String> out = new HashMap<>();
                for (var e : json.entrySet()) {
                    out.put(e.getKey(), e.getValue().getAsString());
                }
                entries = Map.copyOf(out);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return entries;
    }
}
