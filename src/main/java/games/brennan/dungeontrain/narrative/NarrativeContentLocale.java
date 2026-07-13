package games.brennan.dungeontrain.narrative;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.Optional;

/**
 * Holds the <b>content locale</b> the bundled narrative prose is currently served in — the
 * host-language key that {@link StoryRegistry}, {@link RandomBookRegistry},
 * {@link StartingBookRegistry} and {@link DeathLoreStore} consult while (re)loading to decide
 * whether to overlay a localized variant on top of the English base file.
 *
 * <p>Datapacks aren't per-client-language and the prose loaders run through the world-global
 * server-data channel, so there is no per-player choice to make: the whole world's bundled prose
 * follows the <b>host</b> player's Minecraft locale (the same host-first policy
 * {@link WorldLanguage} already applies to relay-delivered player content). {@code current} is
 * {@code ""} for English (the default, and the value at server start before any player is online);
 * a non-empty value like {@code "zh_cn"} means "prefer the bundled
 * {@code data/dungeontrain/narrative_localizations/<locale>/…} variant of each file, falling back
 * to English when a given file has no variant".</p>
 *
 * <p>{@link NarrativeLocaleWatcher} owns the value — it resolves the host locale on a throttled
 * server tick and, when it changes, re-runs the four registries' {@code load(ResourceManager)}
 * so the overlay is re-applied. English hosts keep {@code current == ""} and never pay for an
 * overlay lookup.</p>
 */
public final class NarrativeContentLocale {

    /** Prefix under {@code data/<namespace>/} that holds per-locale prose overlays. */
    static final String ROOT = "narrative_localizations";
    /** Base-content directory prefix stripped when mapping a base id to its overlay path. */
    private static final String NARRATIVES_PREFIX = "narratives/";
    private static final String JSON_EXT = ".json";

    /**
     * The active content locale. {@code ""} = English (no overlay). Written only by
     * {@link NarrativeLocaleWatcher} on the server thread; read on the same thread during a
     * registry reload, so a plain {@code volatile} is sufficient (no compound updates).
     */
    private static volatile String current = "";

    private NarrativeContentLocale() {}

    /** The active content locale, or {@code ""} for English. Never null. */
    public static String current() {
        return current;
    }

    /** Set the active content locale ({@code ""} → English). Normalises null to {@code ""}. */
    public static void set(String locale) {
        current = (locale == null) ? "" : locale;
    }

    /**
     * Whether {@code locale} actually ships a prose overlay tree in {@code resourceManager}
     * (bundled or datapack-supplied). Used by the watcher to avoid switching {@code current}
     * to an unsupported locale (which would trigger a pointless all-English reload).
     */
    public static boolean isSupported(ResourceManager resourceManager, String locale) {
        if (locale == null || locale.isEmpty()) return false;
        return !resourceManager
            .listResources(ROOT + "/" + locale, rl -> rl.getPath().endsWith(JSON_EXT))
            .isEmpty();
    }

    /**
     * The localized variant {@link Resource} for a base content file, or {@link Optional#empty()}
     * when the active locale is English or that particular file has no variant — in which case the
     * caller parses the English base as usual.
     *
     * @param resourceManager the live server-data manager (respects datapack override precedence)
     * @param baseId          the base file's id (its full resource location minus {@code .json}),
     *                        e.g. {@code dungeontrain:narratives/stories/questions}
     * @param dir             the registry's base directory, e.g. {@code narratives/stories} or
     *                        {@code death_lore}
     */
    public static Optional<Resource> localized(ResourceManager resourceManager, ResourceLocation baseId, String dir) {
        String loc = current;
        if (loc.isEmpty()) return Optional.empty();
        String path = baseId.getPath();
        String prefix = dir + "/";
        if (!path.startsWith(prefix)) return Optional.empty();
        String rest = path.substring(prefix.length()); // e.g. "questions" or "end/begin_at_the_end"
        // Strip the shared "narratives/" segment so overlays live at a flat per-category root
        // (stories/, random_books/, starting_books/…, death_lore/) that never collides with
        // starting_books' own context subfolders.
        String category = dir.startsWith(NARRATIVES_PREFIX) ? dir.substring(NARRATIVES_PREFIX.length()) : dir;
        String overlayPath = ROOT + "/" + loc + "/" + category + "/" + rest + JSON_EXT;
        return resourceManager.getResource(
            ResourceLocation.fromNamespaceAndPath(baseId.getNamespace(), overlayPath));
    }
}
