package games.brennan.dungeontrain.narrative;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Page-shape tests for {@link LeaderboardBookFactory}. Exercises the layout only — no ItemStack, no
 * NBT — so the arithmetic that decides how many ranks land on which page is checked without a game.
 */
class LeaderboardBookFactoryTest {

    private static final LeaderboardCategory CAT = LeaderboardCategory.LIVES;

    private static List<LeaderboardPool.Entry> entries(int n) {
        List<LeaderboardPool.Entry> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) out.add(new LeaderboardPool.Entry("Player" + i, 1000L - i));
        return out;
    }

    /** Rendered lines of one page, header included. */
    private static String[] lines(Component page) {
        return page.getString().split("\n", -1);
    }

    @Test
    @DisplayName("a full board fills every page and closes on the reader's standing")
    void fullBoardFillsTheBook() {
        List<Component> pages = LeaderboardBookFactory.pages(
            CAT, entries(LeaderboardBookFactory.MAX_ROWS), Optional.of(new LeaderboardPool.Standing(4, 12, 0)));

        assertEquals(LeaderboardBookFactory.PAGES + 1, pages.size(), "eight board pages plus the closing one");
        // Page one spends lines on the heading, so it carries fewer ranks than the rest. Two lines a
        // rank, plus the heading line and the blank under it.
        assertEquals(LeaderboardBookFactory.FIRST_PAGE_ROWS * LeaderboardBookFactory.LINES_PER_ENTRY + 2,
            lines(pages.get(0)).length);
        assertEquals(LeaderboardBookFactory.ROWS_PER_PAGE * LeaderboardBookFactory.LINES_PER_ENTRY,
            lines(pages.get(1)).length);
    }

    @Test
    @DisplayName("each rank is a name line with its score on the line below")
    void everyRankIsTwoLines() {
        List<Component> pages = LeaderboardBookFactory.pages(CAT, entries(6), Optional.empty());
        String[] first = lines(pages.get(0));

        // [0] heading, [1] blank, then name/score pairs.
        assertTrue(first[2].startsWith("1. Player1"), "name line: " + first[2]);
        assertTrue(first[3].endsWith("999"), "score line under it: '" + first[3] + "'");
        assertTrue(first[3].startsWith(" "), "the score is pushed right, not left: '" + first[3] + "'");
        assertTrue(first[4].startsWith("2. Player2"), "next name line: " + first[4]);
    }

    @Test
    @DisplayName("ranks are numbered continuously across the page break")
    void ranksRunContinuouslyAcrossPages() {
        List<Component> pages = LeaderboardBookFactory.pages(CAT, entries(30), Optional.empty());
        String[] first = lines(pages.get(0));
        String[] second = lines(pages.get(1));

        assertTrue(first[2].startsWith("1. Player1"), first[2]);
        // Last PAIR of page one is the final rank it carries: its name line, then its score.
        assertTrue(first[first.length - 2].startsWith(LeaderboardBookFactory.FIRST_PAGE_ROWS + ". "),
            "last name line of page one: " + first[first.length - 2]);
        assertTrue(second[0].startsWith((LeaderboardBookFactory.FIRST_PAGE_ROWS + 1) + ". "),
            "first line of page two: " + second[0]);
    }

    @Test
    @DisplayName("a short board ends early rather than padding out blank pages")
    void shortBoardEndsEarly() {
        List<Component> pages = LeaderboardBookFactory.pages(CAT, entries(3), Optional.empty());
        assertEquals(2, pages.size(), "one page of ranks plus the closing page");
        assertEquals(8, lines(pages.get(0)).length, "heading, blank, three ranks of two lines each");
    }

    @Test
    @DisplayName("a board deeper than the book is truncated, not wrapped past the last page")
    void oversizeBoardTruncates() {
        List<Component> pages = LeaderboardBookFactory.pages(
            CAT, entries(LeaderboardBookFactory.MAX_ROWS + 500), Optional.empty());
        assertEquals(LeaderboardBookFactory.PAGES + 1, pages.size());
    }

    @Test
    @DisplayName("no laid-out line ever exceeds the page width")
    void everyLineFitsThePage() {
        List<Component> pages = LeaderboardBookFactory.pages(
            CAT, List.of(new LeaderboardPool.Entry("MMMMMMMMMMMMMMMM", 999999L),
                         new LeaderboardPool.Entry("日本語のプレイヤー", 1L),
                         new LeaderboardPool.Entry("i", 5L)),
            Optional.empty());
        // Skip the heading and the blank under it: the heading is a translatable the game wraps for
        // itself, and with no language loaded it renders as its own (very long) key.
        String[] laid = lines(pages.get(0));
        for (int i = 2; i < laid.length; i++) {
            assertTrue(BookColumnLayout.width(laid[i]) <= BookColumnLayout.PAGE_WIDTH_PX,
                "over the margin: '" + laid[i] + "'");
        }
    }

    @Test
    @DisplayName("a reader past the relay's rank horizon is told their score, not a made-up position")
    void beyondTheHorizonSaysSo() {
        List<Component> exact = LeaderboardBookFactory.pages(
            CAT, entries(3), Optional.of(new LeaderboardPool.Standing(4, 12, 0)));
        List<Component> beyond = LeaderboardBookFactory.pages(
            CAT, entries(3), Optional.of(new LeaderboardPool.Standing(0, 12, 10000)));
        List<Component> absent = LeaderboardBookFactory.pages(CAT, entries(3), Optional.empty());

        // Three distinct closing lines — a horizon must never render as if it were a rank.
        String a = last(exact), b = last(beyond), c = last(absent);
        assertTrue(!a.equals(b) && !b.equals(c) && !a.equals(c),
            "expected three different closing lines, got: " + a + " / " + b + " / " + c);
    }

    private static String last(List<Component> pages) {
        return pages.get(pages.size() - 1).getString();
    }

    @Test
    @DisplayName("an empty board produces no book at all rather than an empty one")
    void emptyBoardProducesNothing() {
        LeaderboardPool.clear();
        assertTrue(LeaderboardBookFactory.build(CAT, null).isEmpty());
        assertTrue(LeaderboardBookFactory.roll(1234L, null).isEmpty());
    }

    @Test
    @DisplayName("the same seed always rolls the same board, so a stack never changes its subject")
    void seedIsStable() {
        LeaderboardPool.clear();
        LeaderboardPool.applyBoard(LeaderboardCategory.LIVES, "{\"rows\":[{\"name\":\"Ada\",\"score\":3}]}");
        LeaderboardPool.applyBoard(LeaderboardCategory.BOOKS_READ, "{\"rows\":[{\"name\":\"Grace\",\"score\":9}]}");

        for (long seed : new long[]{0L, 1L, 99L, -7L, Long.MAX_VALUE}) {
            String first = LeaderboardBookFactory.roll(seed, null).orElseThrow().getHoverName().getString();
            for (int i = 0; i < 5; i++) {
                assertEquals(first, LeaderboardBookFactory.roll(seed, null).orElseThrow().getHoverName().getString());
            }
        }
        LeaderboardPool.clear();
    }
}
