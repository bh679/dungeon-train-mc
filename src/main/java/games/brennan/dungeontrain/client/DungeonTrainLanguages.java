package games.brennan.dungeontrain.client;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Set;

/**
 * The set of Minecraft locale codes that Dungeon Train ships a bundled translation for —
 * every {@code assets/dungeontrain/lang/<code>.json} except the English base ({@code en_us}).
 *
 * <p>Used by {@code LanguageSelectEntryLogoMixin} to badge those languages with the DT logo in
 * the vanilla language-selection list, so players can see at a glance which languages the mod is
 * localized into. Derived from the loaded client resources (not hard-coded), so it stays correct
 * as languages are added or supplied by a resource pack.</p>
 */
public final class DungeonTrainLanguages {

    private static final String LANG_DIR = "lang";
    private static final String NAMESPACE = "dungeontrain";
    private static final String JSON = ".json";
    private static final String ENGLISH_BASE = "en_us";

    /** Cached once populated; only a non-empty result is cached so an early (pre-reload) call retries. */
    private static volatile Set<String> cache;

    private DungeonTrainLanguages() {}

    /** Whether Dungeon Train ships a dedicated (non-English) translation for {@code localeCode}. */
    public static boolean isTranslated(String localeCode) {
        if (localeCode == null || localeCode.isEmpty()) return false;
        Set<String> set = cache;
        if (set == null) {
            set = compute();
            if (!set.isEmpty()) cache = set;
        }
        return set.contains(localeCode);
    }

    /** Drop the cached set so it is recomputed on next query (e.g. after a resource reload). */
    public static void invalidate() {
        cache = null;
    }

    private static Set<String> compute() {
        try {
            var resourceManager = Minecraft.getInstance().getResourceManager();
            var found = resourceManager.listResources(
                LANG_DIR,
                rl -> rl.getNamespace().equals(NAMESPACE) && rl.getPath().endsWith(JSON));
            Set<String> out = new HashSet<>();
            for (ResourceLocation rl : found.keySet()) {
                String path = rl.getPath();                 // e.g. "lang/es_es.json"
                int slash = path.lastIndexOf('/');
                String code = path.substring(slash + 1, path.length() - JSON.length());
                if (!code.equals(ENGLISH_BASE)) out.add(code);
            }
            return out;
        } catch (Throwable t) {
            return Set.of();
        }
    }
}
