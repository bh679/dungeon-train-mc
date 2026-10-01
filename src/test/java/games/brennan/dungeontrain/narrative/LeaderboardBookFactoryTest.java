package games.brennan.dungeontrain.narrative;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
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

    // ---- retired eras -----------------------------------------------------------

    private static final String ERAS = "{\"eras\":["
        + "{\"id\":\"v0.0\",\"kind\":\"version\",\"label\":\"The First Era\",\"minVersion\":\"0.0.0\",\"current\":false},"
        + "{\"id\":\"v0.1013\",\"kind\":\"version\",\"label\":\"After the balancing\",\"minVersion\":\"0.1013\",\"current\":false},"
        + "{\"id\":\"v0.1020\",\"kind\":\"version\",\"label\":\"Newest\",\"minVersion\":\"0.1020\",\"current\":true},"
        + "{\"id\":\"y2025\",\"kind\":\"year\",\"label\":\"2025\",\"current\":false}]}";

    @Test
    @DisplayName("one roll in RETIRED_ONE_IN lands on a retired board when any are on hand, never when none are")
    void retiredShare() {
        List<LeaderboardCategory> current = List.of(LeaderboardCategory.LIVES, LeaderboardCategory.BOOKS_READ);
        List<LeaderboardPool.BoardKey> retired = List.of(
            new LeaderboardPool.BoardKey(LeaderboardCategory.DISTANCE_RUN, "v0.0"),
            new LeaderboardPool.BoardKey(LeaderboardCategory.PLAYTIME_RUN, "y2025"));
        int retiredPicks = 0;
        int n = 4000;
        for (long i = 0; i < n; i++) {
            LeaderboardPool.BoardKey k = LeaderboardBookFactory.pick(i * 0x9E3779B97F4A7C15L + 17L, current, retired).orElseThrow();
            if (!k.isCurrent()) {
                retiredPicks++;
                assertTrue(retired.contains(k));
            } else {
                assertTrue(current.contains(k.category()));
            }
        }
        double share = retiredPicks / (double) n;
        assertTrue(share > 0.012 && share < 0.04, "retired share " + share + " (expected 1 in " + LeaderboardBookFactory.RETIRED_ONE_IN + ")");
        for (long i = 0; i < 200; i++) {
            assertTrue(LeaderboardBookFactory.pick(i, current, List.of()).orElseThrow().isCurrent());
            assertTrue(!LeaderboardBookFactory.pick(i, List.of(), retired).orElseThrow().isCurrent(),
                "only retired boards on hand: always one of those");
        }
        assertTrue(LeaderboardBookFactory.pick(5L, List.of(), List.of()).isEmpty());
    }

    @Test
    @DisplayName("a retired era's book names the era on page one and after the reader's line; the current one does not")
    void retiredBookSaysSo() {
        LeaderboardPool.clear();
        LeaderboardPool.applyEras(ERAS);
        List<Component> current = LeaderboardBookFactory.pages(CAT, LeaderboardPool.CURRENT, entries(3), Optional.empty());
        List<Component> version = LeaderboardBookFactory.pages(CAT, "v0.0", entries(3), Optional.empty());
        List<Component> year = LeaderboardBookFactory.pages(CAT, "y2025", entries(3), Optional.empty());
        List<Component> unknown = LeaderboardBookFactory.pages(CAT, "v0.5", entries(3), Optional.empty());

        // No language is loaded here, so the wording is checked by component structure, not text.
        assertEquals(LeaderboardBookFactory.heading(CAT), LeaderboardBookFactory.heading(CAT, LeaderboardPool.CURRENT));
        TranslatableContents v = (TranslatableContents) LeaderboardBookFactory.heading(CAT, "v0.0").getContents();
        assertEquals(LeaderboardBookFactory.ERA_KEY, v.getKey());
        assertEquals(LeaderboardBookFactory.heading(CAT), v.getArgs()[0], "the plain heading is still there");
        // The era is named by VERSIONS, never by the relay's free-text label: the founding era is
        // "before <first floor>", a middle era is "<its floor> up to <next floor>", the newest
        // retired era with no successor listed is "<its floor> onward".
        TranslatableContents vName = (TranslatableContents) ((Component) v.getArgs()[1]).getContents();
        assertEquals(LeaderboardBookFactory.ERA_VERSION_BEFORE_KEY, vName.getKey());
        assertEquals("0.1013", ((Component) vName.getArgs()[0]).getString());
        TranslatableContents mid = (TranslatableContents) ((Component) ((TranslatableContents)
            LeaderboardBookFactory.heading(CAT, "v0.1013").getContents()).getArgs()[1]).getContents();
        assertEquals(LeaderboardBookFactory.ERA_VERSION_RANGE_KEY, mid.getKey());
        assertEquals("0.1013", ((Component) mid.getArgs()[0]).getString());
        assertEquals("0.1020", ((Component) mid.getArgs()[1]).getString());
        TranslatableContents last = (TranslatableContents) LeaderboardBookFactory.eraName("v0.1020").getContents();
        assertEquals(LeaderboardBookFactory.ERA_VERSION_ONWARD_KEY, last.getKey());
        TranslatableContents y = (TranslatableContents) LeaderboardBookFactory.heading(CAT, "y2025").getContents();
        assertEquals(LeaderboardBookFactory.ERA_YEAR_KEY, ((TranslatableContents) ((Component) y.getArgs()[1]).getContents()).getKey());
        TranslatableContents u = (TranslatableContents) LeaderboardBookFactory.heading(CAT, "v0.5").getContents();
        assertEquals("v0.5", ((Component) u.getArgs()[1]).getString(), "an era the relay never described is named by id");
        assertTrue(lines(year.get(0)).length > 1 && lines(unknown.get(0)).length > 1);
        assertEquals(current.size(), version.size(), "the era wording adds no page");

        String currentClose = current.get(current.size() - 1).getString();
        String versionClose = version.get(version.size() - 1).getString();
        // Unranked on a closed list says so without "not yet"; ranked lines are unchanged.
        TranslatableContents unranked = (TranslatableContents) version.get(version.size() - 1).getContents();
        assertEquals(LeaderboardBookFactory.ERA_YOU_UNRANKED_KEY, unranked.getKey());
        assertEquals(LeaderboardCategory.YOU_UNRANKED_KEY,
            ((TranslatableContents) current.get(current.size() - 1).getContents()).getKey());
        Optional<LeaderboardPool.Standing> second = Optional.of(new LeaderboardPool.Standing(2, 900L, 0));
        List<Component> ranked = LeaderboardBookFactory.pages(CAT, "v0.0", entries(3), second);
        List<Component> rankedCurrent = LeaderboardBookFactory.pages(CAT, entries(3), second);
        assertTrue(ranked.get(ranked.size() - 1).getString()
            .startsWith(rankedCurrent.get(rankedCurrent.size() - 1).getString()), "the reader's own line comes first, unchanged");
        assertTrue(versionClose.length() > currentClose.length(), "then the closed-list line");
        assertEquals("The Tallyman", LeaderboardBookFactory.author(LeaderboardPool.CURRENT));
        assertEquals("The Tallyman, before v0.1013", LeaderboardBookFactory.author("v0.0"));
        assertEquals("The Tallyman, v0.1013", LeaderboardBookFactory.author("v0.1013"));
        assertEquals("The Tallyman, 2025", LeaderboardBookFactory.author("y2025"));
        assertEquals("The Tallyman, v0.5", LeaderboardBookFactory.author("v0.5"));
        LeaderboardPool.clear();
    }

    @Test
    @DisplayName("a retired-era book builds from that era's board and stays a Tallyman book")
    void buildsRetiredBook() {
        LeaderboardPool.clear();
        LeaderboardPool.applyEras(ERAS);
        LeaderboardPool.applyBoard(new LeaderboardPool.BoardKey(LeaderboardCategory.DISTANCE_RUN, "v0.0"),
            "{\"rows\":[{\"name\":\"Grace\",\"score\":9000}]}");
        assertTrue(LeaderboardBookFactory.build(LeaderboardCategory.DISTANCE_RUN, null).isEmpty(), "no current board");
        var stack = LeaderboardBookFactory.build(LeaderboardCategory.DISTANCE_RUN, "v0.0", null).orElseThrow();
        assertEquals(LeaderboardBookFactory.RETIRED_TITLE, stack.getHoverName().getString(),
            "a retired book is titled for what it is; the board is named on page one");
        assertTrue(LeaderboardBookFactory.RETIRED_TITLE.length() <= BookFactory.MAX_TITLE_CHARS);
        assertTrue(LeaderboardBookTag.is(stack));
        // and with only retired boards on hand, a roll finds one
        assertTrue(LeaderboardBookFactory.roll(42L, null).isPresent());
        LeaderboardPool.clear();
    }
}
