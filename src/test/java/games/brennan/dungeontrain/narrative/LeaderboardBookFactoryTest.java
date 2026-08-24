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
            CAT, entries(LeaderboardBookFactory.MAX_ROWS), Optional.of(new LeaderboardPool.Standing(4, 12)));

        assertEquals(LeaderboardBookFactory.PAGES + 1, pages.size(), "eight board pages plus the closing one");
        // Page one spends lines on the heading, so it carries fewer ranks than the rest.
        assertEquals(LeaderboardBookFactory.FIRST_PAGE_ROWS + 2, lines(pages.get(0)).length);
        assertEquals(LeaderboardBookFactory.LINES_PER_PAGE, lines(pages.get(1)).length);
    }

    @Test
    @DisplayName("ranks are numbered continuously across the page break")
    void ranksRunContinuouslyAcrossPages() {
        List<Component> pages = LeaderboardBookFactory.pages(CAT, entries(30), Optional.empty());
        String[] first = lines(pages.get(0));
        String[] second = lines(pages.get(1));

        assertTrue(first[2].startsWith("1. Player1"), first[2]);
        assertTrue(first[first.length - 1].startsWith(LeaderboardBookFactory.FIRST_PAGE_ROWS + ". "),
            "last line of page one: " + first[first.length - 1]);
        assertTrue(second[0].startsWith((LeaderboardBookFactory.FIRST_PAGE_ROWS + 1) + ". "),
            "first line of page two: " + second[0]);
    }

    @Test
    @DisplayName("a short board ends early rather than padding out blank pages")
    void shortBoardEndsEarly() {
        List<Component> pages = LeaderboardBookFactory.pages(CAT, entries(3), Optional.empty());
        assertEquals(2, pages.size(), "one page of ranks plus the closing page");
        assertEquals(5, lines(pages.get(0)).length, "heading, blank, three ranks");
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
        for (String line : lines(pages.get(0))) {
            if (line.isEmpty() || !Character.isDigit(line.charAt(0))) continue; // heading, not a rank
            assertTrue(BookColumnLayout.width(line) <= BookColumnLayout.PAGE_WIDTH_PX,
                "over the margin: '" + line + "'");
        }
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
