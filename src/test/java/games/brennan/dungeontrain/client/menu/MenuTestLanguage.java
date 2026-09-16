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
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Installs the shipped {@code en_us.json} as the active {@link Language} before every test class,
 * so a menu test can assert on the English a player reads now that {@link MenuLang} resolves every
 * label through it. Applied with {@code @ExtendWith(MenuTestLanguage.class)} on every test class
 * under {@code client/menu} — a global {@code META-INF/services} registration was tried first and
 * ran in a different class loader from the tests under the dev classpath's union filesystem, so
 * its {@link Language} was not theirs. Idempotent, so the overlay is built once per JVM.
 *
 * <p>Tests that inject their own language (the narrative render tests) still work: they capture
 * whatever {@link Language#getInstance()} is at construction and restore it afterwards.</p>
 */
public final class MenuTestLanguage implements BeforeAllCallback {

    private static final Path EN_US = Path.of("src/main/resources/assets/dungeontrain/lang/en_us.json");
    private static boolean installed;

    @Override
    public void beforeAll(ExtensionContext context) {
        install();
    }

    public static synchronized void install() {
        if (installed) {
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(
                Files.readString(RepoPaths.root().resolve(EN_US), StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> entries = new HashMap<>();
            for (var e : json.entrySet()) {
                entries.put(e.getKey(), e.getValue().getAsString());
            }
            Language.inject(new OverlayLanguage(Language.getInstance(), entries));
            installed = true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
