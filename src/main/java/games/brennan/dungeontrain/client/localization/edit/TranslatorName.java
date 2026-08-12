package games.brennan.dungeontrain.client.localization.edit;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The name a translator wants to be credited under, remembered between sessions.
 *
 * <p>Separate from the Minecraft account name on purpose: this is what gets written into
 * {@code localization/authors.json} and the shipped {@code translation_contributors.json} when a
 * contribution lands, and so is what appears on the in-game Credits page. Community translators
 * have credited names ({@code 老本願}, {@code 阿世xAsh}) that are not their Minecraft usernames.</p>
 *
 * <p>A one-line file rather than a config option: it belongs to the translator, not the game, and
 * nothing else in the mod needs to read it.</p>
 */
public final class TranslatorName {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String FILE_NAME = "translator-name.txt";
    private static final int MAX_CHARS = 80;

    private static String cached;

    private TranslatorName() {}

    /** The remembered name, or {@code ""}. Never null. */
    public static synchronized String get() {
        if (cached != null) {
            return cached;
        }
        cached = "";
        try {
            Path file = file();
            if (file != null && Files.isRegularFile(file)) {
                cached = clean(Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] Translations: could not read the translator name — {}",
                e.toString());
        }
        return cached;
    }

    /** Remember {@code name} (or forget it, when blank). Never throws. */
    public static synchronized void set(String name) {
        String value = clean(name);
        cached = value;
        try {
            Path file = file();
            if (file == null) {
                return;
            }
            if (value.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] Translations: could not save the translator name — {}",
                e.toString());
        }
    }

    private static Path file() {
        try {
            return TranslationOverrideStore.root().resolve(FILE_NAME);
        } catch (Exception e) {
            return null;
        }
    }

    /** Trimmed, single-line, length-capped — this ends up in a credits file and a JSON body. */
    private static String clean(String name) {
        if (name == null) {
            return "";
        }
        String value = name.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() > MAX_CHARS ? value.substring(0, MAX_CHARS) : value;
    }
}
