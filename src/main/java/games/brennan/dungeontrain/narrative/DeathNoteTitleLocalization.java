package games.brennan.dungeontrain.narrative;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Server-side set of localized "Death Note" trigger titles. Backs the multilingual title check in
 * {@link DeathNoteTitle#isDeathNoteTitle(String, Collection)}: a book titled with <em>any</em>
 * language's Death Note name triggers the curse, for <em>every</em> player regardless of their
 * client locale (the canonical English "death note" is always accepted by {@link DeathNoteTitle} on
 * top). All comparisons ignore case and whitespace — the stored titles are
 * {@link DeathNoteTitle#normalize normalized}, and the signed title is normalized the same way.
 *
 * <h2>Single source of truth: the instruction book</h2>
 * The game ships a random loot book that teaches players how to make a Death Note
 * ({@code data/dungeontrain/narratives/random_books/deathnote.json}, whose per-locale overlays live
 * at {@code narrative_localizations/<locale>/random_books/deathnote.json}). The word that book is
 * <em>titled</em> is exactly the word a player must name their own book — so every localized trigger
 * is derived straight from those books' titles. There is no separate trigger file to keep in sync:
 * one translated book drives both the shown instructions and the trigger, and they cannot drift
 * because they are the same string. Adding a new language's book overlay automatically enables that
 * language's title as a trigger — for all players.
 *
 * <p>The English base title ("Deathnote") is intentionally not stored — it normalizes to
 * {@code deathnote}, which {@link DeathNoteTitle} already accepts everywhere.</p>
 *
 * <p>Reads the server-data channel (bundled data + datapack overrides) via {@link ResourceManager}.
 * Registered by {@link NarrativeDataLoaders}, so it honours {@code /reload}. The load body is plain
 * {@link ResourceManager} code, matching the other narrative loaders.</p>
 */
public final class DeathNoteTitleLocalization {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Root of the per-locale narrative overlays (shared with {@link NarrativeContentLocale#ROOT}). */
    private static final String ROOT = NarrativeContentLocale.ROOT;
    /** The instruction book whose title defines the trigger word: {@code random_books/deathnote}. */
    static final String INSTRUCTION_BOOK_SUFFIX = "/random_books/deathnote.json";
    /**
     * The most characters a player can type into a book title. Vanilla's book edit screen filters the
     * title field with {@code p -> p.length() < 16} ({@code BookEditScreen#titleEdit}), so a title of
     * 16+ characters can never be typed — a trigger derived from one would be unreachable. Any longer
     * localized title is skipped (with a warning) rather than becoming a silent dead trigger.
     */
    static final int VANILLA_MAX_TITLE_CHARS = 15;

    /**
     * Every localized trigger title, normalized. Rebuilt wholesale on each reload and swapped in
     * atomically (immutable value; no in-place mutation). {@code volatile} suffices: written on the
     * server thread during reload, read on the server thread while signing.
     */
    private static volatile Set<String> allTitles = Set.of();

    private DeathNoteTitleLocalization() {}

    /**
     * Every localized Death Note trigger title (normalized), across all languages. Empty until the
     * first reload. English is <em>not</em> included here — {@link DeathNoteTitle} accepts it
     * unconditionally.
     */
    public static Collection<String> all() {
        return allTitles;
    }

    /**
     * Reload the set by scanning every per-locale instruction-book overlay through the datapack
     * channel (bundled data + datapack overrides). Called by the reload listener at world load /
     * {@code /reload} and by {@code /dungeontrain narrative reload}. Builds a fresh set and swaps it
     * in — never mutates the live one.
     */
    public static void load(ResourceManager resourceManager) {
        Set<String> next = new LinkedHashSet<>();
        Map<ResourceLocation, Resource> resources =
            resourceManager.listResources(ROOT, rl -> rl.getPath().endsWith(INSTRUCTION_BOOK_SUFFIX));
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation file = entry.getKey();
            try (InputStream in = entry.getValue().open();
                 Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                String title = titleFrom(r);
                // Skip an absent/blank title or one that reduces to the always-accepted English form.
                if (!title.isEmpty()) {
                    next.add(title);
                }
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] deathnote trigger: failed reading '{}': {}", file, e.toString());
            }
        }
        allTitles = Set.copyOf(next);
        LOGGER.info("[DungeonTrain] deathnote localized triggers loaded — {} title(s)", allTitles.size());
    }

    /** Drop the set (called on server stop). */
    public static void clear() {
        allTitles = Set.of();
    }

    /**
     * The normalized {@code title} of an instruction-book JSON body, or {@code ""} when the title is
     * absent/blank, a placeholder ({@code "Untitled"}), or reduces to the English {@code deathnote}
     * form (already accepted everywhere). Never throws — malformed JSON yields {@code ""}.
     */
    static String titleFrom(Reader reader) {
        JsonElement root;
        try {
            root = JsonParser.parseReader(reader);
        } catch (Exception e) {
            return "";
        }
        if (!root.isJsonObject()) return "";
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("title") || !obj.get("title").isJsonPrimitive()
                || !obj.getAsJsonPrimitive("title").isString()) {
            return "";
        }
        String raw = obj.get("title").getAsString();
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("Untitled")) return "";
        // A title a player can't type is a dead trigger — warn loudly instead of shipping it silently.
        if (raw.strip().length() > VANILLA_MAX_TITLE_CHARS) {
            LOGGER.warn("[DungeonTrain] deathnote trigger: title \"{}\" exceeds {} chars — a player cannot"
                    + " type it as a book title, so it is NOT a usable Death Note trigger. Shorten it.",
                    raw, VANILLA_MAX_TITLE_CHARS);
            return "";
        }
        // "Deathnote" / "Death Note" (English base) reduce to the always-accepted form — nothing to add.
        return DeathNoteTitle.isDeathNoteTitle(raw) ? "" : DeathNoteTitle.normalize(raw);
    }
}
