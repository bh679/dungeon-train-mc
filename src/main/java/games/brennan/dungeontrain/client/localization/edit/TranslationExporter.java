package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes a locale's translatable text out to disk so it can be worked on outside the game —
 * in a spreadsheet, a text editor, or by someone who does not play Minecraft at all.
 *
 * <p>Two formats, each chosen so the returned files drop straight into the repo pipeline:</p>
 * <ul>
 *   <li><b>UI keys → CSV</b> with exactly the columns
 *       {@code scripts/localization/build-review-package.py} emits, so a filled-in file can be
 *       fed to {@code apply-review-csv.py} unchanged. Two files, matching that script's split:
 *       Dungeon Train's own keys and the sibling mods' keys.</li>
 *   <li><b>Books → JSON</b>, mirroring the real
 *       {@code data/dungeontrain/narrative_localizations/&lt;locale&gt;/} tree. A translator edits
 *       the prose in place and the result is a drop-in replacement. Deliberately not the repo's
 *       {@code BOOK_COLUMNS} CSV, which is a per-book verdict sheet carrying no prose at all.</li>
 * </ul>
 *
 * <p>Exports reflect what the player currently sees: their own overrides are written into
 * {@code new_translation} with {@code verdict: fixed}, so the file round-trips their in-game
 * work rather than throwing it away.</p>
 */
public final class TranslationExporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Byte-for-byte the {@code COLUMNS} list in build-review-package.py. */
    static final List<String> COLUMNS = List.of(
        "key", "reason", "author", "reviewer", "english", "current_translation",
        "english_changed_at", "reviewed_at", "new_translation", "verdict");

    /** The {@code reason} value build-review-package.py uses for unreviewed machine output. */
    private static final String NEEDS_FIRST_REVIEW = "needs_first_review";
    private static final String VERDICT_FIXED = "fixed";

    private static final String DT_NAMESPACE = "dungeontrain";
    private static final String DT_CSV = "dungeontrain-ui.csv";
    private static final String SIBLINGS_CSV = "siblings-ui.csv";
    private static final String BOOKS_DIR = "books";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private TranslationExporter() {}

    /** What an export produced, for the screen to report. */
    public record Result(int uiRows, int siblingRows, int books, Path directory, String error) {
        public boolean ok() {
            return error == null;
        }

        public int total() {
            return uiRows + siblingRows + books;
        }
    }

    /** {@code <config>/dungeontrain/translations/export/<locale>/}. */
    public static Path directoryFor(String locale) {
        return TranslationOverrideStore.root().resolve("export").resolve(locale);
    }

    /**
     * Write every editable unit for {@code locale}. Overwrites a previous export — the files are
     * a snapshot of the current state, and keeping stale ones around invites re-importing work
     * that has already been superseded.
     */
    public static Result export(String locale) {
        if (!TranslationScreen.isEditable(locale)) {
            return new Result(0, 0, 0, null, "locale");
        }
        Path dir = directoryFor(locale);
        try {
            Files.createDirectories(dir);
            List<TranslationUnit> units = TranslationCatalog.forLocale(locale);
            TranslationEdits edits = TranslationOverrides.mergedFor(locale);

            int ui = writeCsv(dir.resolve(DT_CSV), units, edits, true);
            int siblings = writeCsv(dir.resolve(SIBLINGS_CSV), units, edits, false);
            int books = writeBooks(dir.resolve(BOOKS_DIR), locale, units, edits);
            LOGGER.info("[DungeonTrain] Translations: exported {} to {}", locale, dir);
            return new Result(ui, siblings, books, dir, null);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: export failed — {}", e.toString());
            return new Result(0, 0, 0, dir, e.toString());
        }
    }

    /**
     * One CSV of lang units. {@code dungeonTrainOnly} splits DT's keys from the siblings' the
     * same way the repo script does, because the two have different return legs: DT's English is
     * here to diff against, the siblings' lives in their own repos.
     */
    private static int writeCsv(Path file, List<TranslationUnit> units, TranslationEdits edits,
                                boolean dungeonTrainOnly) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add(Csv.writeRow(COLUMNS));
        int rows = 0;
        for (TranslationUnit unit : units) {
            if (unit.type() != TranslationUnit.Type.LANG) {
                continue;
            }
            if (DT_NAMESPACE.equals(unit.namespace()) != dungeonTrainOnly) {
                continue;
            }
            String override = edits.lang().get(unit.id());
            lines.add(Csv.writeRow(List.of(
                dungeonTrainOnly ? unit.id() : unit.namespace() + ":" + unit.id(),
                unit.aiUnreviewed() ? NEEDS_FIRST_REVIEW : "",
                // author / reviewer / the two timestamps are repo-side provenance the jar does
                // not carry — only the AI-unreviewed bit survives into the shipped manifest.
                "", "",
                unit.source(),
                override != null ? override : unit.shipped(),
                "", "",
                override != null ? override : "",
                override != null ? VERDICT_FIXED : "")));
            rows++;
        }
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return rows;
    }

    /**
     * Rewrite each book with the player's overrides folded in, preserving the file's structure
     * (ids, weights, translator notes) so the result is a valid drop-in for the repo tree.
     */
    private static int writeBooks(Path booksDir, String locale, List<TranslationUnit> units,
                                  TranslationEdits edits) throws Exception {
        Map<String, Map<String, String>> overridesByBook = new TreeMap<>();
        for (TranslationUnit unit : units) {
            if (unit.type() != TranslationUnit.Type.BOOK) {
                continue;
            }
            overridesByBook.computeIfAbsent(unit.bookPath(), k -> new LinkedHashMap<>());
            String override = edits.books().get(unit.id());
            if (override != null) {
                overridesByBook.get(unit.bookPath()).put(unit.bookField(), override);
            }
        }
        if (overridesByBook.isEmpty()) {
            return 0;
        }
        Files.createDirectories(booksDir);
        int written = 0;
        for (Map.Entry<String, Map<String, String>> entry : overridesByBook.entrySet()) {
            String source = ModJarResources.read(
                "data/dungeontrain/narrative_localizations/" + locale + "/" + entry.getKey() + ".json");
            if (source == null) {
                continue;
            }
            JsonElement root = JsonParser.parseString(source);
            NarrativeBookWriter.apply(root, entry.getValue());
            Path file = booksDir.resolve(entry.getKey() + ".json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
            written++;
        }
        return written;
    }
}
