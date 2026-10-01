package games.brennan.dungeontrain.client.localization.edit;

import games.brennan.dungeontrain.narrative.LeaderboardCategory;
import games.brennan.dungeontrain.narrative.LeaderboardPool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Gathers what {@link BookPreviewContext} needs from the client: the other strings of the book a
 * string belongs to, in this locale with edits applied, and a leaderboard to lay out.
 *
 * <p>Values are the editor's own view of each string — the saved override, else what the locale
 * ships, else the English — so the preview reads the way the rest of the editor does.</p>
 */
final class BookPreviewSiblings {

    /** Names for a board the relay has not sent yet — plainly samples, never mistaken for players. */
    private static final List<String> SAMPLE_NAMES = List.of(
        "Steve", "Alex", "Faulthurst", "Ari", "Efe", "Kai", "Makena", "Noor", "Sunny", "Zuri",
        "Brennan", "Tallyman", "Pip", "Rowan", "Sage", "Wren");
    private static final int SAMPLE_ROWS = 24;

    private BookPreviewSiblings() {}

    /** Every lang key starting {@code prefix} → its value in {@code locale}. */
    static Map<String, String> lang(String locale, String prefix) {
        TranslationEdits edits = TranslationOverrides.mergedFor(locale);
        Map<String, String> out = new LinkedHashMap<>();
        for (TranslationUnit unit : TranslationCatalog.forLocale(locale)) {
            if (unit.type() == TranslationUnit.Type.LANG && unit.id().startsWith(prefix)) {
                out.put(unit.id(), valueOf(unit, edits));
            }
        }
        return out;
    }

    /** Every field of the narrative book at {@code bookPath} → its value in {@code locale}. */
    static Map<String, String> bookFields(String locale, String bookPath) {
        TranslationEdits edits = TranslationOverrides.mergedFor(locale);
        Map<String, String> out = new LinkedHashMap<>();
        for (TranslationUnit unit : TranslationCatalog.forLocale(locale)) {
            if (unit.type() == TranslationUnit.Type.BOOK && bookPath.equals(unit.bookPath())) {
                out.put(unit.bookField(), valueOf(unit, edits));
            }
        }
        return out;
    }

    private static String valueOf(TranslationUnit unit, TranslationEdits edits) {
        String override = TranslationFilters.overrideOf(unit, edits);
        if (override != null && !override.isEmpty()) {
            return override;
        }
        return unit.shipped() == null || unit.shipped().isEmpty() ? unit.source() : unit.shipped();
    }

    // ---- leaderboards ----------------------------------------------------------------------

    /**
     * A leaderboard book around {@code editedKey}: one of the boards that uses it (rolled from
     * {@code seed} when several do), its real rows if the relay has sent them, sample rows if not.
     * Asks the relay for the board as a side effect, so Refresh can show the real one once it lands.
     */
    static BookPreviewContext.Preview leaderboard(String locale, String editedKey, String typed, long seed) {
        Random rnd = new Random(seed);
        List<LeaderboardCategory> boards = boardsUsing(editedKey);
        LeaderboardCategory board = boards.get(rnd.nextInt(boards.size()));
        LeaderboardPool.refresh(board);

        List<BookPreviewContext.Row> rows = new ArrayList<>();
        List<LeaderboardPool.Entry> entries = LeaderboardPool.board(board).entries();
        if (entries.isEmpty()) {
            long score = 500 + rnd.nextInt(5_000);
            for (int i = 0; i < SAMPLE_ROWS; i++) {
                rows.add(new BookPreviewContext.Row(SAMPLE_NAMES.get(rnd.nextInt(SAMPLE_NAMES.size())),
                    board.render(score)));
                score = Math.max(1, score - 1 - rnd.nextInt(Math.max(1, (int) score / 6)));
            }
        } else {
            for (LeaderboardPool.Entry entry : entries) {
                rows.add(new BookPreviewContext.Row(entry.name(), board.render(entry.score())));
            }
        }

        String closingKey = closingKey(editedKey, rnd);
        String score = board.render(1 + rnd.nextInt(400));
        List<String> args = switch (closingKey) {
            case LeaderboardCategory.YOU_KEY -> List.of(Integer.toString(1 + rnd.nextInt(50)), score);
            case LeaderboardCategory.YOU_BEYOND_KEY -> List.of(score, "100");
            default -> List.of();
        };
        return BookPreviewContext.leaderboard(editedKey, typed,
            lang(locale, BookPreviewContext.LEADERBOARD_PREFIX), board.title(), board.headerKey(),
            board.scopeKey(), rows, closingKey, args);
    }

    /** The boards whose book prints {@code key}; every board for the standing lines they all share. */
    private static List<LeaderboardCategory> boardsUsing(String key) {
        List<LeaderboardCategory> out = new ArrayList<>();
        for (LeaderboardCategory board : LeaderboardCategory.values()) {
            if (key.equals(board.headerKey()) || key.equals(board.scopeKey())) {
                out.add(board);
            }
        }
        return out.isEmpty() ? List.of(LeaderboardCategory.values()) : out;
    }

    private static String closingKey(String editedKey, Random rnd) {
        List<String> closings = List.of(LeaderboardCategory.YOU_KEY, LeaderboardCategory.YOU_BEYOND_KEY,
            LeaderboardCategory.YOU_UNRANKED_KEY);
        return closings.contains(editedKey) ? editedKey : closings.get(rnd.nextInt(closings.size()));
    }
}
