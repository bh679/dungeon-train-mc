package games.brennan.dungeontrain.client.localization;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.localization.edit.ProvenanceManifestRegistry;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.narrative.HumanOnlyProse;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * The "Human translations only" opt-out: with it on, every Dungeon Train line that is still
 * machine-translated and unreviewed in the player's language is shown in English instead.
 *
 * <p>Nothing new is decided here about what is human. The shipped provenance manifests
 * ({@link ProvenanceManifestRegistry}) already say, per key and per book, which lines are AI output
 * nobody has read; this turns that list into an English override layer for lang keys and a
 * {@link HumanOnlyProse} snapshot for the prose the integrated server loads. A line not flagged —
 * written or reviewed by a person — stays translated, and so do relay-approved translations and the
 * player's own edits, which {@code TranslationOverrides} lays over this layer.</p>
 *
 * <p>Falling back to English is the right floor because it is the one the game already has: a key a
 * language does not carry at all shows in English by vanilla's own fallback.</p>
 */
public final class HumanOnlyTranslations {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SOURCE_LOCALE = "en_us";

    /** Locale the cached layer was built for, and the layer itself. Cleared on every resource reload. */
    private static String cachedLocale;
    private static Map<String, String> cached = Map.of();

    private HumanOnlyTranslations() {}

    public static boolean isEnabled() {
        return ClientDisplayConfig.isHumanOnlyTranslations();
    }

    /** Drop the cached layer — the lang files or manifests it was built from may have changed. */
    public static synchronized void invalidate() {
        cachedLocale = null;
        cached = Map.of();
    }

    /**
     * The English replacements to lay under the player's own overrides for {@code locale}: every
     * flagged key mapped to its en_us text. Empty when the option is off, for any English, and for a
     * language with no manifest (a third-party pack's provenance is unknown, so it is left alone).
     */
    public static synchronized Map<String, String> englishFallbacks(ResourceManager resources, String locale) {
        String code = locale == null ? "" : locale.toLowerCase(Locale.ROOT);
        if (!isEnabled() || code.isEmpty() || code.startsWith("en_") || resources == null) {
            return Map.of();
        }
        if (code.equals(cachedLocale)) {
            return cached;
        }
        Map<String, Map<String, String>> english = new HashMap<>();
        for (String namespace : ProvenanceManifestRegistry.langNamespaces(code)) {
            english.put(namespace, loadEnglish(resources, namespace));
        }
        cached = Map.copyOf(fallbacks(english,
            (namespace, key) -> ProvenanceManifestRegistry.isAiUnreviewedLang(code, namespace, key)));
        cachedLocale = code;
        LOGGER.info("[DungeonTrain] Human translations only: {} line(s) of '{}' shown in English.",
            cached.size(), code);
        return cached;
    }

    /**
     * Every key {@code flagged} names, mapped to its English. Pure, for testing: no resources, no
     * registries.
     *
     * @param englishByNamespace namespace → (key → English)
     * @param flagged            whether a key in a namespace is AI-unreviewed
     */
    static Map<String, String> fallbacks(Map<String, Map<String, String>> englishByNamespace,
                                         BiPredicate<String, String> flagged) {
        Map<String, String> out = new LinkedHashMap<>();
        englishByNamespace.forEach((namespace, english) -> english.forEach((key, value) -> {
            if (flagged.test(namespace, key)) {
                out.put(key, value);
            }
        }));
        return out;
    }

    /**
     * {@code fallbacks} with {@code overrides} laid over them — the override wins wherever both name
     * a key, because a relay approval or the player's own edit is human by definition.
     */
    public static Map<String, String> layer(Map<String, String> fallbacks, Map<String, String> overrides) {
        if (fallbacks.isEmpty()) {
            return overrides;
        }
        Map<String, String> out = new HashMap<>(fallbacks);
        out.putAll(overrides);
        return out;
    }

    /**
     * Tell the integrated server (if this client hosts one) which prose to serve in English. A
     * dedicated server never sees this — its prose follows its own host and stays as it was.
     */
    public static void publishProse(String locale) {
        String code = locale == null ? "" : locale.toLowerCase(Locale.ROOT);
        if (!isEnabled() || code.isEmpty() || code.startsWith("en_")
            || !ProvenanceManifestRegistry.hasData(code)) {
            HumanOnlyProse.clear();
            return;
        }
        HumanOnlyProse.set(code,
            path -> ProvenanceManifestRegistry.isAiUnreviewedBook(code, path),
            ProvenanceManifestRegistry.isWholeNamespaceAiUnreviewed(code, "adventureitemnames"));
    }

    /** {@code namespace}'s en_us lang file across the whole pack stack, later packs winning. */
    private static Map<String, String> loadEnglish(ResourceManager resources, String namespace) {
        Map<String, String> out = new HashMap<>();
        ResourceLocation file = ResourceLocation.fromNamespaceAndPath(namespace,
            "lang/" + SOURCE_LOCALE + ".json");
        for (Resource resource : resources.getResourceStack(file)) {
            try (InputStream in = resource.open()) {
                Language.loadFromJson(in, out::put);
            } catch (Exception e) {
                LOGGER.warn("[DungeonTrain] Human translations only: could not read {} from {} — {}",
                    file, resource.sourcePackId(), e.toString());
            }
        }
        return out;
    }
}
