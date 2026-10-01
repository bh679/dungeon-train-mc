package games.brennan.dungeontrain.client.localization.edit;

import games.brennan.dungeontrain.narrative.BookColumnLayout;
import games.brennan.dungeontrain.narrative.BookFactory;
import games.brennan.dungeontrain.narrative.LeaderboardBookFactory;
import games.brennan.dungeontrain.narrative.StartingBookFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The whole page or book a translated string belongs to, rebuilt the way the game builds it — the
 * book preview's "Full Context" view.
 *
 * <p>Many book strings are only a piece of what the player reads: a stat book page is an opener, a
 * stat line and a tail; a leaderboard's heading sits above its ranks; a title is never on a page at
 * all, only on the cover. This puts the edited string back among its siblings, marked
 * {@link Segment#highlighted}, so the translator sees it where it lands.</p>
 *
 * <p>Pure: the screen hands in the sibling values (this locale, edits applied) and a seed, and
 * {@code Refresh} is just a new seed. Sibling values come from the editor's merged view, so a field
 * this locale never translated reads in English here — a convenience; the game itself uses the whole
 * locale file or the codec's defaults.</p>
 */
public final class BookPreviewContext {

    /** A run of page text; segments on one page are separated by a blank line, as the game joins them. */
    public record Segment(String text, boolean highlighted) {}

    public record Page(List<Segment> segments) {
        static Page plain(String text) {
            return new Page(List.of(new Segment(text, false)));
        }
    }

    /** The book's name and "by" line, which the game shows on the item rather than a page. */
    public record Cover(String title, String author, boolean titleHighlighted, boolean authorHighlighted) {}

    /**
     * @param cover       null for books whose cover is not translated text (stat, leaderboard)
     * @param deathScreen the lines are shown on the death screen, not in a book
     * @param rolled      part of what is shown was picked at random, so Refresh can show another
     */
    public record Preview(Cover cover, List<Page> pages, boolean deathScreen, boolean rolled) {}

    /** Which family of book a string belongs to — decides how its context is rebuilt. */
    public enum Source { STAT_BOOK, LEADERBOARD, NARRATIVE, DEATH_LORE, NONE }

    public static final String STAT_BOOK_PREFIX = "book.dungeontrain.statbook.";
    public static final String LEADERBOARD_PREFIX = "dungeontrain.leaderboard.";
    static final String DEATH_LORE_PREFIX = "death_lore/";
    static final String STARTING_BOOKS_PREFIX = "starting_books";
    static final String STORIES_PREFIX = "stories/";

    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:(\\d+)\\$)?[sd]");
    private static final Pattern BOOK_BODY = Pattern.compile("variants\\.\\d+");
    private static final Pattern LETTER_BODY = Pattern.compile("letters\\.(\\d+)\\.variants\\.\\d+");
    private static final Pattern LETTER_FIELD = Pattern.compile("letters\\.(\\d+)\\..+");
    private static final Pattern LETTER_NOTE = Pattern.compile("letters\\.\\d+\\.notes\\..*");
    /** Death-screen order of an entry's lines (NarrativeDeathScreen). */
    static final List<String> DEATH_LORE_ORDER = List.of("question", "subline", "narration", "epitaph");

    private static final String UNTITLED = "Untitled";
    private static final String ANONYMOUS = "Anonymous";
    private static final String SAMPLE_PLAYER = "Steve";

    private BookPreviewContext() {}

    // ---- which context ---------------------------------------------------------------------

    /** Which family {@code unit} belongs to; NONE when it has no wider page or book to show. */
    public static Source sourceOf(TranslationUnit unit) {
        if (unit == null || unit.id() == null) {
            return Source.NONE;
        }
        if (unit.type() == TranslationUnit.Type.BOOK) {
            String path = unit.bookPath();
            String field = unit.bookField();
            if (path.startsWith(DEATH_LORE_PREFIX)) {
                return field.contains(".") ? Source.DEATH_LORE : Source.NONE;
            }
            // Story notes are editable but the game never prints them.
            return LETTER_NOTE.matcher(field).matches() ? Source.NONE : Source.NARRATIVE;
        }
        String key = unit.id();
        if (key.startsWith(STAT_BOOK_PREFIX + "open.") || key.startsWith(STAT_BOOK_PREFIX + "stat.")
            || key.startsWith(STAT_BOOK_PREFIX + "tail.")) {
            return Source.STAT_BOOK;
        }
        return key.startsWith(LEADERBOARD_PREFIX) ? Source.LEADERBOARD : Source.NONE;
    }

    /**
     * The "just this line" pages for {@code text}: the paginator the game uses for it — starting books
     * break on {@code %PAGE%}, everything else packs paragraphs.
     */
    public static List<String> justThisPages(TranslationUnit unit, String text) {
        boolean explicit = unit != null && unit.type() == TranslationUnit.Type.BOOK
            && unit.bookPath().startsWith(STARTING_BOOKS_PREFIX);
        List<String> pages = explicit ? StartingBookFactory.paginateExplicit(text) : BookFactory.paginate(text);
        return pages.isEmpty() ? List.of("") : pages;
    }

    // ---- stat book -------------------------------------------------------------------------

    /**
     * The stat book's one page — opener, stat line, tail, joined by a blank line
     * ({@code RunStatBookFactory#pages}) — with {@code editedKey} holding {@code typed} and the other
     * two slots rolled from {@code seed}.
     *
     * @param lang every {@code book.dungeontrain.statbook.*} key's value in this locale
     */
    public static Preview statBook(String editedKey, String typed, Map<String, String> lang,
                                   String playerName, long seed) {
        Random rnd = new Random(seed);
        String player = playerName == null || playerName.isBlank() ? SAMPLE_PLAYER : playerName;
        List<Segment> page = new ArrayList<>();
        page.add(statSlot("open.", editedKey, typed, lang, rnd, key -> player));
        page.add(statSlot("stat.", editedKey, typed, lang, rnd,
            key -> key.endsWith(".one") ? "1" : Integer.toString(2 + rnd.nextInt(98))));
        page.add(statSlot("tail.", editedKey, typed, lang, rnd, key -> ""));
        return new Preview(null, List.of(new Page(page)), false, true);
    }

    private static Segment statSlot(String slot, String editedKey, String typed, Map<String, String> lang,
                                    Random rnd, java.util.function.Function<String, String> arg) {
        String prefix = STAT_BOOK_PREFIX + slot;
        if (editedKey.startsWith(prefix)) {
            return new Segment(fill(typed, arg.apply(editedKey)), true);
        }
        List<String> keys = lang.keySet().stream().filter(k -> k.startsWith(prefix)).sorted().toList();
        if (keys.isEmpty()) {
            return new Segment("", false);
        }
        String key = keys.get(rnd.nextInt(keys.size()));
        return new Segment(fill(lang.get(key), arg.apply(key)), false);
    }

    // ---- leaderboard -----------------------------------------------------------------------

    /** One rank as the board lists it: the name and its already-rendered score. */
    public record Row(String name, String score) {}

    /**
     * A leaderboard book laid out as {@code LeaderboardBookFactory#pages} does: heading and the first
     * ranks, then pages of ranks, then the reader's standing on a page of its own.
     *
     * @param title      the book's cover title ({@code LeaderboardCategory#title}, English in game too)
     * @param scopeKey   the span wrapper around the header, or null for a board without one
     * @param closingKey which standing line closes the book
     * @param closingArgs its placeholder values, in order
     */
    public static Preview leaderboard(String editedKey, String typed, Map<String, String> lang,
                                      String title, String headerKey, String scopeKey, List<Row> rows,
                                      String closingKey, List<String> closingArgs) {
        String header = valueOf(headerKey, editedKey, typed, lang);
        String heading = scopeKey == null ? header : fill(valueOf(scopeKey, editedKey, typed, lang), header);
        boolean headingEdited = editedKey.equals(headerKey) || editedKey.equals(scopeKey);

        List<String> lines = new ArrayList<>();
        int shown = Math.min(rows.size(), LeaderboardBookFactory.MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            lines.add(BookColumnLayout.truncate((i + 1) + ". " + rows.get(i).name(),
                BookColumnLayout.PAGE_WIDTH_PX));
            lines.add(BookColumnLayout.rightAlign(rows.get(i).score()));
        }
        List<Page> pages = new ArrayList<>();
        int at = 0;
        for (int p = 0; p < LeaderboardBookFactory.PAGES && at < lines.size(); p++) {
            int room = (p == 0 ? LeaderboardBookFactory.FIRST_PAGE_ROWS : LeaderboardBookFactory.ROWS_PER_PAGE)
                * LeaderboardBookFactory.LINES_PER_ENTRY;
            int end = Math.min(lines.size(), at + room);
            Segment body = new Segment(String.join("\n", lines.subList(at, end)), false);
            pages.add(new Page(p == 0 ? List.of(new Segment(heading, headingEdited), body) : List.of(body)));
            at = end;
        }
        if (pages.isEmpty()) {
            pages.add(new Page(List.of(new Segment(heading, headingEdited))));
        }
        String closing = fill(valueOf(closingKey, editedKey, typed, lang), closingArgs.toArray(String[]::new));
        pages.add(new Page(List.of(new Segment(closing, editedKey.equals(closingKey)))));
        return new Preview(new Cover(title, LeaderboardBookFactory.AUTHOR, false, false), pages, false, true);
    }

    private static String valueOf(String key, String editedKey, String typed, Map<String, String> lang) {
        if (key == null) {
            return "";
        }
        return key.equals(editedKey) ? typed : lang.getOrDefault(key, key);
    }

    // ---- narrative books -------------------------------------------------------------------

    /**
     * The narrative book {@code editedField} belongs to: its cover (title and author, by the game's
     * fallback rules) and the body's pages. A body field is its own book; a cover field shows a body
     * rolled from {@code seed}.
     *
     * @param fields the book's flattened fields ({@link NarrativeBookFields#flatten}) in this locale
     */
    public static Preview narrative(String bookPath, String editedField, String typed,
                                    Map<String, String> fields, long seed) {
        Map<String, String> book = new java.util.LinkedHashMap<>(fields);
        book.put(editedField, typed);
        Random rnd = new Random(seed);
        boolean story = bookPath.startsWith(STORIES_PREFIX);

        String bodyField;
        boolean rolled = false;
        if (BOOK_BODY.matcher(editedField).matches() || LETTER_BODY.matcher(editedField).matches()) {
            bodyField = editedField;
        } else {
            List<String> bodies = bodyFields(book, story, letterOf(editedField));
            rolled = bodies.size() > 1;
            bodyField = bodies.isEmpty() ? null : bodies.get(rnd.nextInt(bodies.size()));
        }
        String body = bodyField == null ? "" : book.getOrDefault(bodyField, "");
        List<Page> pages = new ArrayList<>();
        for (String page : bookPath.startsWith(STARTING_BOOKS_PREFIX)
            ? StartingBookFactory.paginateExplicit(body) : BookFactory.paginate(body)) {
            pages.add(Page.plain(page));
        }
        if (pages.isEmpty()) {
            pages.add(Page.plain(""));
        }
        return new Preview(cover(bookPath, editedField, book, story, letterOf(bodyField)), pages, false, rolled);
    }

    private static List<String> bodyFields(Map<String, String> book, boolean story, String letter) {
        List<String> out = new ArrayList<>();
        for (String field : book.keySet()) {
            Matcher m = LETTER_BODY.matcher(field);
            boolean body = story ? m.matches() && (letter == null || letter.equals(m.group(1)))
                : BOOK_BODY.matcher(field).matches();
            if (body) {
                out.add(field);
            }
        }
        return out;
    }

    /** The letter index a story field sits under, or null. */
    private static String letterOf(String field) {
        if (field == null) {
            return null;
        }
        Matcher m = LETTER_FIELD.matcher(field);
        return m.matches() ? m.group(1) : null;
    }

    /** {@code BookFactory#preferredTitle} for stories, {@code RandomBookFactory} for the rest. */
    private static Cover cover(String bookPath, String edited, Map<String, String> book, boolean story,
                               String letter) {
        if (story) {
            String storyTitle = book.getOrDefault("story", "");
            String labelField = "letters." + letter + ".label";
            boolean useStory = !storyTitle.isBlank() && !UNTITLED.equals(storyTitle);
            String title = useStory ? storyTitle : book.getOrDefault(labelField, "");
            String author = orDefault(book.get("character"), ANONYMOUS);
            return new Cover(clamp(title), author,
                useStory ? edited.equals("story") : edited.equals(labelField), edited.equals("character"));
        }
        String title = book.getOrDefault("title", "");
        if (title.isBlank() || UNTITLED.equals(title)) {
            title = bookPath.substring(bookPath.lastIndexOf('/') + 1);
        }
        return new Cover(clamp(title), orDefault(book.get("author"), ANONYMOUS),
            edited.equals("title"), edited.equals("author"));
    }

    private static String clamp(String title) {
        return title.length() <= BookFactory.MAX_TITLE_CHARS ? title : title.substring(0, BookFactory.MAX_TITLE_CHARS);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    // ---- death lore ------------------------------------------------------------------------

    /** One death-screen entry's lines in the order the screen draws them, on one page. */
    public static Preview deathLore(String editedField, String typed, Map<String, String> fields) {
        String index = editedField.substring(0, editedField.indexOf('.'));
        List<Segment> lines = new ArrayList<>();
        for (String part : DEATH_LORE_ORDER) {
            String field = index + "." + part;
            String value = field.equals(editedField) ? typed : fields.get(field);
            if (value != null && !value.isBlank()) {
                lines.add(new Segment(value, field.equals(editedField)));
            }
        }
        return new Preview(null, List.of(new Page(lines)), true, false);
    }

    // ---- placeholders ----------------------------------------------------------------------

    /** {@code text} with {@code %s}/{@code %d}/{@code %1$s} filled from {@code args}; {@code %%} → {@code %}. */
    static String fill(String text, String... args) {
        if (text == null) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        int next = 0;
        while (m.find()) {
            int index = m.group(1) != null ? Integer.parseInt(m.group(1)) - 1 : next++;
            String value = index >= 0 && index < args.length ? args[index] : m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString().replace("%%", "%");
    }
}
