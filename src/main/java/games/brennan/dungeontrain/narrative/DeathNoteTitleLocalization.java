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
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Server-side sets of localized note trigger titles, one set per {@link NoteKind}. Backs the
 * multilingual title check in {@link NoteKind#of}: a book titled with <em>any</em> language's Death
 * Note or Love Note name triggers that mechanic, for <em>every</em> player regardless of their
 * client locale (the canonical English names are always accepted on top). All comparisons ignore
 * case and whitespace — the stored titles are {@link DeathNoteTitle#normalize normalized}, and the
 * signed title is normalized the same way.
 *
 * <h2>Single source of truth: the instruction book</h2>
 * The game ships a random loot book per kind teaching players how to make one
 * ({@code data/dungeontrain/narratives/random_books/{deathnote,lovenote}.json}, whose per-locale
 * overlays live at {@code narrative_localizations/<locale>/random_books/<kind>.json}). The word
 * that book is <em>titled</em> is exactly the word a player must name their own book — so every
 * localized trigger is derived straight from those books' titles. There is no separate trigger file
 * to keep in sync: one translated book drives both the shown instructions and the trigger, and they
 * cannot drift because they are the same string. Adding a new language's book overlay automatically
 * enables that language's title as a trigger — for all players.
 *
 * <p>The English base titles ("Deathnote" / "Lovenote") are intentionally not stored — they
 * normalize to the forms {@link NoteKind} already accepts everywhere.</p>
 *
 * <p>Reads the server-data channel (bundled data + datapack overrides) via {@link ResourceManager}.
 * Registered by {@link NarrativeDataLoaders}, so it honours {@code /reload}. The load body is plain
 * {@link ResourceManager} code, matching the other narrative loaders.</p>
 */
public final class DeathNoteTitleLocalization {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Root of the per-locale narrative overlays (shared with {@link NarrativeContentLocale#ROOT}). */
    private static final String ROOT = NarrativeContentLocale.ROOT;
    /**
     * The most characters a player can type into a book title. Vanilla's book edit screen filters the
     * title field with {@code p -> p.length() < 16} ({@code BookEditScreen#titleEdit}), so a title of
     * 16+ characters can never be typed — a trigger derived from one would be unreachable. Any longer
     * localized title is skipped (with a warning) rather than becoming a silent dead trigger.
     */
    static final int VANILLA_MAX_TITLE_CHARS = 15;

    /**
     * Every localized trigger title, normalized, keyed by the kind it triggers. Rebuilt wholesale on
     * each reload and swapped in atomically (immutable value; no in-place mutation). {@code volatile}
     * suffices: written on the server thread during reload, read on the server thread while signing.
     */
    private static volatile Map<NoteKind, Set<String>> titlesByKind = Map.of();

    private DeathNoteTitleLocalization() {}

    /**
     * Every localized trigger title for {@code kind} (normalized), across all languages. Empty until
     * the first reload. English is <em>not</em> included here — {@link NoteKind} accepts each kind's
     * canonical English name unconditionally.
     */
    public static Collection<String> all(NoteKind kind) {
        return titlesByKind.getOrDefault(kind, Set.of());
    }

    /**
     * Reload every kind's set by scanning that kind's per-locale instruction-book overlays through
     * the datapack channel (bundled data + datapack overrides). Called by the reload listener at
     * world load / {@code /reload} and by {@code /dungeontrain narrative reload}. Builds fresh sets
     * and swaps them in — never mutates the live ones.
     */
    public static void load(ResourceManager resourceManager) {
        Map<NoteKind, Set<String>> next = new EnumMap<>(NoteKind.class);
        for (NoteKind kind : NoteKind.values()) {
            next.put(kind, loadKind(resourceManager, kind));
        }
        titlesByKind = Map.copyOf(next);
        for (NoteKind kind : NoteKind.values()) {
            LOGGER.info("[DungeonTrain] {} localized triggers loaded — {} title(s)",
                    kind.id(), all(kind).size());
        }
    }

    /** The normalized localized trigger titles found for one {@code kind}. Never throws. */
    private static Set<String> loadKind(ResourceManager resourceManager, NoteKind kind) {
        Set<String> next = new LinkedHashSet<>();
        Map<ResourceLocation, Resource> resources =
            resourceManager.listResources(ROOT, rl -> rl.getPath().endsWith(kind.instructionBookSuffix()));
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation file = entry.getKey();
            try (InputStream in = entry.getValue().open();
                 Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                String title = titleFrom(r, kind);
                // Skip an absent/blank title or one that reduces to the always-accepted English form.
                if (!title.isEmpty()) {
                    next.add(title);
                }
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] {} trigger: failed reading '{}': {}",
                        kind.id(), file, e.toString());
            }
        }
        return Set.copyOf(next);
    }

    /** Drop every set (called on server stop). */
    public static void clear() {
        titlesByKind = Map.of();
    }

    /**
     * The normalized {@code title} of {@code kind}'s instruction-book JSON body, or {@code ""} when
     * the title is absent/blank, a placeholder ({@code "Untitled"}), or reduces to that kind's
     * English form (already accepted everywhere). Never throws — malformed JSON yields {@code ""}.
     */
    static String titleFrom(Reader reader, NoteKind kind) {
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
            LOGGER.warn("[DungeonTrain] {} trigger: title \"{}\" exceeds {} chars — a player cannot"
                    + " type it as a book title, so it is NOT a usable {} trigger. Shorten it.",
                    kind.id(), raw, VANILLA_MAX_TITLE_CHARS, kind.trophyTitle());
            return "";
        }
        // The English base ("Deathnote" / "Love Note") reduces to the always-accepted form — nothing to add.
        String normalized = DeathNoteTitle.normalize(raw);
        return normalized.equals(kind.englishTitle()) ? "" : normalized;
    }
}
