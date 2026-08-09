package games.brennan.dungeontrain.client.localization.edit;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads translation work done outside the game back in.
 *
 * <p>The drop-a-file-in-a-folder shape {@code UserContentImporter} already established for
 * editor packages — Minecraft has no file picker, so the player puts files in
 * {@code translations/import/} and presses a button. Accepts both export formats: the review
 * CSVs, and the {@code narrative_localizations}-shaped book JSON tree.</p>
 *
 * <p>Imports land as the player's own local overrides, which means the normal precedence and
 * revert paths apply to them — an import is not a special kind of edit, just a faster way to
 * make a lot of them.</p>
 */
public final class TranslationImporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String BOOKS_DIR = "books";
    private static final String CSV_EXT = ".csv";
    private static final String JSON_EXT = ".json";
    /** Depth cap on the walk: books nest at most one folder deep under {@code books/}. */
    private static final int MAX_WALK_DEPTH = 6;

    private TranslationImporter() {}

    /** The outcome of an import — counts are of values that actually differ from what we had. */
    public record Result(int langChanged, int bookChanged, int skipped, Path directory,
                         String error) {
        public boolean ok() {
            return error == null;
        }

        public int changed() {
            return langChanged + bookChanged;
        }
    }

    /** {@code <config>/dungeontrain/translations/import/<locale>/}. */
    public static Path directoryFor(String locale) {
        return TranslationOverrideStore.root().resolve("import").resolve(locale);
    }

    /**
     * Read everything under the import directory for {@code locale} and apply it as local
     * overrides. Creates the directory when it is missing so the "open folder" button always has
     * somewhere to open.
     */
    public static Result importAll(String locale) {
        if (!TranslationScreen.isEditable(locale)) {
            return new Result(0, 0, 0, null, "locale");
        }
        Path dir = directoryFor(locale);
        try {
            Files.createDirectories(dir);
            Map<String, String> lang = new LinkedHashMap<>();
            Map<String, String> books = new LinkedHashMap<>();
            int skipped = readCsvFiles(dir, lang);
            skipped += readBookFiles(dir.resolve(BOOKS_DIR), locale, books);

            TranslationEdits current = TranslationOverrides.local();
            TranslationEdits updated = current;
            int langChanged = 0;
            int bookChanged = 0;
            for (Map.Entry<String, String> entry : lang.entrySet()) {
                TranslationEdits next = updated.withLang(entry.getKey(), entry.getValue());
                if (!next.lang().equals(updated.lang())) {
                    langChanged++;
                    updated = next;
                }
            }
            for (Map.Entry<String, String> entry : books.entrySet()) {
                TranslationEdits next = updated.withBook(entry.getKey(), entry.getValue());
                if (!next.books().equals(updated.books())) {
                    bookChanged++;
                    updated = next;
                }
            }
            if (langChanged + bookChanged > 0 && !TranslationOverrides.replaceLocal(updated)) {
                return new Result(0, 0, skipped, dir, "save");
            }
            LOGGER.info("[DungeonTrain] Translations: imported {} change(s) for {} from {}",
                langChanged + bookChanged, locale, dir);
            return new Result(langChanged, bookChanged, skipped, dir, null);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: import failed — {}", e.toString());
            return new Result(0, 0, 0, dir, e.toString());
        }
    }

    /**
     * Every {@code *.csv} directly in {@code dir}, merged into {@code out}. Returns how many rows
     * were skipped — a row with no {@code new_translation} is a translator saying "leave this
     * one", not an error, and is the overwhelmingly common case in a partly-filled file.
     */
    private static int readCsvFiles(Path dir, Map<String, String> out) throws Exception {
        int skipped = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(CSV_EXT)).toList()) {
                skipped += readCsv(file, out);
            }
        }
        return skipped;
    }

    private static int readCsv(Path file, Map<String, String> out) {
        int skipped = 0;
        try {
            List<List<String>> rows = Csv.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (rows.isEmpty()) {
                return 0;
            }
            Map<String, Integer> columns = headerIndex(rows.get(0));
            Integer keyCol = columns.get("key");
            Integer valueCol = columns.get("new_translation");
            if (keyCol == null || valueCol == null) {
                LOGGER.warn("[DungeonTrain] Translations: {} has no key/new_translation columns; "
                    + "skipping it.", file.getFileName());
                return 0;
            }
            Integer verdictCol = columns.get("verdict");
            for (List<String> row : rows.subList(1, rows.size())) {
                if (row.size() <= Math.max(keyCol, valueCol)) {
                    skipped++;
                    continue;
                }
                String verdict = verdictCol != null && row.size() > verdictCol
                    ? row.get(verdictCol).trim().toLowerCase(java.util.Locale.ROOT) : "";
                String value = row.get(valueCol);
                // "skip" is an explicit decline; an empty new_translation means "unchanged".
                if ("skip".equals(verdict) || value.isBlank()) {
                    skipped++;
                    continue;
                }
                // The siblings' CSV qualifies keys as <namespace>:<key>; at runtime the lang map
                // is flat and unqualified, so strip it back off.
                String key = row.get(keyCol).trim();
                int colon = key.indexOf(':');
                out.put(colon >= 0 ? key.substring(colon + 1) : key, value);
            }
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: could not read {} — {}", file, e.toString());
        }
        return skipped;
    }

    private static Map<String, Integer> headerIndex(List<String> header) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            columns.put(header.get(i).trim().toLowerCase(java.util.Locale.ROOT), i);
        }
        return columns;
    }

    /**
     * Every book JSON under {@code booksDir}, diffed field-by-field against what the mod ships
     * so only genuinely changed prose becomes an override. Importing a whole file as overrides
     * would mark every untouched line as the player's own work, which would then travel in the
     * submission.
     */
    private static int readBookFiles(Path booksDir, String locale, Map<String, String> out) {
        if (!Files.isDirectory(booksDir)) {
            return 0;
        }
        int skipped = 0;
        try (Stream<Path> walk = Files.walk(booksDir, MAX_WALK_DEPTH)) {
            for (Path file : walk.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(JSON_EXT)).toList()) {
                String bookPath = booksDir.relativize(file).toString()
                    .replace(file.getFileSystem().getSeparator(), "/");
                bookPath = bookPath.substring(0, bookPath.length() - JSON_EXT.length());
                skipped += readBook(file, locale, bookPath, out);
            }
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: could not walk {} — {}",
                booksDir, e.toString());
        }
        return skipped;
    }

    private static int readBook(Path file, String locale, String bookPath,
                                Map<String, String> out) {
        try {
            Map<String, String> imported =
                NarrativeBookFields.flatten(Files.readString(file, StandardCharsets.UTF_8));
            if (imported.isEmpty()) {
                return 1;
            }
            Map<String, String> shipped = NarrativeBookFields.flatten(ModJarResources.read(
                "data/dungeontrain/narrative_localizations/" + locale + "/" + bookPath + JSON_EXT));
            int skipped = 0;
            for (Map.Entry<String, String> field : imported.entrySet()) {
                String was = shipped.get(field.getKey());
                if (was != null && was.equals(field.getValue())) {
                    skipped++;
                    continue;
                }
                out.put(bookPath + "#" + field.getKey(), field.getValue());
            }
            return skipped;
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: could not read {} — {}", file, e.toString());
            return 1;
        }
    }
}
