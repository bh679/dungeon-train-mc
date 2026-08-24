package games.brennan.dungeontrain.client.localization.edit;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Which languages this player has already asked for a machine first draft of.
 *
 * <p>One line per locale in {@code <config>/dungeontrain/translations/requested.txt}, beside the
 * override layers and the dismissals. It exists so the green button can go quiet once it has been
 * pressed: the relay knows the tally, but a client that asks it on every open would show a button
 * that flickers between states while the network answers, and one offline would show a button that
 * looks unpressed no matter how many times it is pressed.</p>
 *
 * <p>A record of intent, not an authority over anything — the relay's count is what decides which
 * languages get translated. Losing this file costs a player nothing but a second press, so it is
 * best-effort throughout, like every other file in this directory.</p>
 */
public final class TranslationRequests {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String FILE = "requested.txt";
    /** There are ~130 locales; well past any honest use, and a bound on a file we rewrite whole. */
    private static final int MAX_ENTRIES = 256;

    private static Set<String> cache;

    private TranslationRequests() {}

    /** Whether this player has already asked for {@code locale}. */
    public static synchronized boolean isRequested(String locale) {
        return !normalise(locale).isEmpty() && load().contains(normalise(locale));
    }

    /**
     * Record that they have. Returns false when it was already recorded or the locale is unusable,
     * so the caller can skip a pointless relay call.
     */
    public static synchronized boolean record(String locale) {
        String code = normalise(locale);
        if (code.isEmpty()) {
            return false;
        }
        Set<String> current = load();
        if (current.contains(code) || current.size() >= MAX_ENTRIES) {
            return false;
        }
        Set<String> next = new LinkedHashSet<>(current);
        next.add(code);
        save(next);
        cache = Set.copyOf(next);
        return true;
    }

    /** Drop the cache — for tests, and for a config directory changed underneath us. */
    public static synchronized void invalidate() {
        cache = null;
    }

    private static Set<String> load() {
        if (cache != null) {
            return cache;
        }
        Set<String> out = new LinkedHashSet<>();
        Path file = file();
        try {
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String code = normalise(line);
                    if (!code.isEmpty() && out.size() < MAX_ENTRIES) {
                        out.add(code);
                    }
                }
            }
        } catch (Exception e) {
            // Reads as "nothing requested", which shows the button again — failing towards one
            // extra press rather than towards a button that can never be found.
            LOGGER.debug("[DungeonTrain] Translations: could not read {} — {}", file, e.toString());
        }
        cache = Set.copyOf(out);
        return cache;
    }

    private static void save(Set<String> codes) {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, codes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] Translations: could not write {} — {}", file, e.toString());
        }
    }

    private static Path file() {
        return TranslationOverrideStore.root().resolve(FILE);
    }

    private static String normalise(String locale) {
        return locale == null ? "" : locale.trim().toLowerCase(Locale.ROOT);
    }
}
