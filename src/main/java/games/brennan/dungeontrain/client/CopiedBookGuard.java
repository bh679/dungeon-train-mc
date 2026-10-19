package games.brennan.dungeontrain.client;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Keeps the supporters' Copy button ({@link BookCopyButton}) from being a book duplicator: it remembers
 * what that button put on the clipboard, and {@code BookEditScreenCopiedPasteMixin} refuses to paste any
 * of it into a Book & Quill.
 *
 * <p>Each copy is remembered as its whole text, its title and every page, all {@link #normalise normalised}
 * so a change of case or spacing doesn't slip past. A paste is refused when it is a chunk of at least
 * {@link #MIN_MATCH_CHARS} characters of any of them, or exactly one of them (a short page). The newest
 * {@link #MAX_BOOKS} copies are kept, and saved to {@code <gameDir>/dungeontrain/copied_books.json}
 * because the clipboard outlives a restart.</p>
 *
 * <p>Retyping, or editing the text elsewhere before pasting, still gets through: this closes the direct
 * copy and paste path only. The relay's duplicate-book detection stays the backstop.</p>
 */
public final class CopiedBookGuard {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    static final int MIN_MATCH_CHARS = 12;
    static final int MAX_BOOKS = 16;
    private static final String FILE = "dungeontrain/copied_books.json";

    /** Oldest first. Each entry is one copy's normalised pieces. Null until loaded. */
    private static List<List<String>> books = null;

    private CopiedBookGuard() {}

    /** Remember one copy: the clipboard text plus its title and pages as separate pieces. */
    public static synchronized void remember(String copiedText, String title, List<String> pages) {
        List<String> pieces = pieces(copiedText, title, pages);
        if (pieces.isEmpty()) return;
        List<List<String>> next = new ArrayList<>(loaded());
        next.remove(pieces);
        next.add(pieces);
        while (next.size() > MAX_BOOKS) next.remove(0);
        books = Collections.unmodifiableList(next);
        save(books);
    }

    /** True when {@code clipboard} is text the Copy button produced, whole or in part. */
    public static synchronized boolean isCopiedText(String clipboard) {
        return matches(loaded(), clipboard);
    }

    /** The normalised, non-blank, distinct pieces of one copy. */
    static List<String> pieces(String copiedText, String title, List<String> pages) {
        List<String> out = new ArrayList<>();
        addPiece(out, copiedText);
        addPiece(out, title);
        if (pages != null) for (String page : pages) addPiece(out, page);
        return Collections.unmodifiableList(out);
    }

    private static void addPiece(List<String> out, String raw) {
        String n = normalise(raw);
        if (!n.isEmpty() && !out.contains(n)) out.add(n);
    }

    static boolean matches(List<List<String>> remembered, String clipboard) {
        String clip = normalise(clipboard);
        if (clip.isEmpty()) return false;
        for (List<String> book : remembered) {
            for (String piece : book) {
                if (piece.equals(clip)) return true;
                if (clip.length() >= MIN_MATCH_CHARS && piece.contains(clip)) return true;
            }
        }
        return false;
    }

    /** Formatting codes gone, every run of whitespace one space, lower case. */
    static String normalise(String s) {
        if (s == null) return "";
        String stripped = ChatFormatting.stripFormatting(s);
        if (stripped == null) return "";
        return stripped.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static List<List<String>> loaded() {
        if (books == null) books = load();
        return books;
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(FILE);
    }

    private static List<List<String>> load() {
        try {
            Path path = file();
            if (!Files.isRegularFile(path)) return List.of();
            List<List<String>> read = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8),
                    new TypeToken<List<List<String>>>() {}.getType());
            if (read == null) return List.of();
            List<List<String>> clean = new ArrayList<>();
            for (List<String> book : read) {
                if (book == null) continue;
                List<String> pieces = new ArrayList<>();
                for (String piece : book) addPiece(pieces, piece);
                if (!pieces.isEmpty()) clean.add(Collections.unmodifiableList(pieces));
            }
            return Collections.unmodifiableList(clean);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Could not read copied-books list; starting empty", e);
            return List.of();
        }
    }

    private static void save(List<List<String>> toSave) {
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(toSave), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Could not save copied-books list", e);
        }
    }
}
