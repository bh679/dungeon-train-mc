package games.brennan.dungeontrain.narrative;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * THROWAWAY dev tool, not a real test. Runs the real {@link LeaderboardBookFactory#pages} over rows
 * dumped from the relay and prints each page, so a /give command can be built from exactly what the
 * factory would lay out. Delete before opening a PR.
 */
class DumpLeaderboardBookTest {

    @Test
    void dump() throws Exception {
        String board = System.getenv("DT_BOARD");
        Path in = Path.of(board == null ? "board.tsv" : board);
        List<LeaderboardPool.Entry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(in)) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t");
            entries.add(new LeaderboardPool.Entry(parts[0], Long.parseLong(parts[1])));
        }

        String catName = System.getenv("DT_CAT");
        LeaderboardCategory cat = LeaderboardCategory.valueOf(catName == null ? "DISTANCE_RUN" : catName);
        List<Component> pages = LeaderboardBookFactory.pages(
                cat, entries, Optional.of(new LeaderboardPool.Standing(12, 9001L, 0)));

        StringBuilder sb = new StringBuilder("@@TITLE@@").append(cat.title()).append('\n');
        for (Component page : pages) {
            sb.append("@@PAGE@@\n").append(page.getString()).append('\n');
        }
        Files.writeString(Path.of(System.getenv("DT_OUT")), sb.toString());
    }

    /**
     * Feed a candidate /give payload through the game's own SNBT parser, so an escaping mistake in a
     * hand-built command surfaces here rather than as an unreadable book in-game. Skipped unless
     * DT_SNBT is set.
     */
    @Test
    void parseSnbt() throws Exception {
        String path = System.getenv("DT_SNBT");
        if (path == null) return;
        String snbt = Files.readString(Path.of(path)).trim();
        String result;
        try {
            result = "OK\n" + net.minecraft.nbt.TagParser.parseTag(snbt);
        } catch (Exception e) {
            result = "FAIL\n" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        Files.writeString(Path.of(System.getenv("DT_SNBT_OUT")), result);
    }
}
