package games.brennan.dungeontrain.data;

import com.google.gson.JsonParser;
import games.brennan.dungeonbackup.api.RestoreMerger;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grow-only merge a restore applies to cross-world progress files that already exist.
 * Each case is one of the three stores' real on-disk shapes, with synthetic values.
 */
class RestoreMergersTest {

    private static String merge(String glob, String live, String backedUp) throws IOException {
        RestoreMerger merger = RestoreMergers.BY_GLOB.get(glob);
        Optional<byte[]> out = merger.merge(
            live.getBytes(StandardCharsets.UTF_8), backedUp.getBytes(StandardCharsets.UTF_8));
        return out.map(b -> new String(b, StandardCharsets.UTF_8)).orElse("<untouched>");
    }

    private static void assertJson(String expected, String actual) {
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(actual));
    }

    @Test
    void advancementsAreTheUnionLiveOrderFirst() throws IOException {
        // The reported bug: a world join after the loss wrote a one-entry sidecar; the backup has
        // the player's real history.
        String live = "{\"granted\":[\"minecraft:story/root\"]}";
        String backup = "{\"granted\":[\"dungeontrain:dungeon_train/carts_100\",\"minecraft:story/root\"]}";

        assertJson("{\"granted\":[\"minecraft:story/root\",\"dungeontrain:dungeon_train/carts_100\"]}",
            merge("achievements/*.json", live, backup));
    }

    @Test
    void statsTakeTheLargerOfEveryCounterNestedOnesIncluded() throws IOException {
        String live = "{\"trainTicks\":50,\"totalDeaths\":3,\"distance\":{\"runs\":1.5,\"blocks\":900.0}}";
        String backup = "{\"trainTicks\":12000,\"totalDeaths\":1,\"totalBooks\":7,"
            + "\"distance\":{\"runs\":400.25,\"blocks\":10.0,\"displacement\":33.0}}";

        assertJson("{\"trainTicks\":12000,\"totalDeaths\":3,\"totalBooks\":7,"
                + "\"distance\":{\"runs\":400.25,\"blocks\":900.0,\"displacement\":33.0}}",
            merge("stats/*.json", live, backup));
    }

    @Test
    void aLegacyStatsBackupIsMigratedBeforeMerging() throws IOException {
        // Pre-nesting backups keep echoes/distance at the top level; they must land in the nested
        // fields the live file uses, not sit beside them.
        String live = "{\"echoes\":{\"encountered\":2},\"distance\":{\"runs\":5.0}}";
        String backup = "{\"totalEchos\":40,\"totalDistance\":800.0}";

        String merged = merge("stats/*.json", live, backup);
        var obj = JsonParser.parseString(merged).getAsJsonObject();
        assertEquals(40, obj.getAsJsonObject("echoes").get("encountered").getAsLong());
        assertEquals(800.0, obj.getAsJsonObject("distance").get("runs").getAsDouble());
    }

    @Test
    void bookBurnCountersShareTheStatsRule() throws IOException {
        assertJson("{\"booksBurnedUnread\":9}",
            merge("stats/*.json", "{\"booksBurnedUnread\":2}", "{\"booksBurnedUnread\":9}"));
    }

    @Test
    void narrativeUnionsEveryLetterAndVariantList() throws IOException {
        String live = "{\"read_letters\":{\"a\":[1]},\"variants_seen\":{}}";
        String backup = "{\"read_letters\":{\"a\":[1,2],\"b\":[3]},\"variants_seen\":{\"a#1\":[0]}}";

        assertJson("{\"read_letters\":{\"a\":[1,2],\"b\":[3]},\"variants_seen\":{\"a#1\":[0]}}",
            merge("narrative/*.json", live, backup));
    }

    @Test
    void anUnreadableSideLeavesTheLiveFileAlone() throws IOException {
        assertEquals("<untouched>", merge("achievements/*.json", "{\"granted\":[]}", "not json {"));
        assertEquals("<untouched>", merge("achievements/*.json", "[]", "{\"granted\":[]}"));
    }

    @Test
    void everyStoreFolderIsCovered() {
        assertTrue(RestoreMergers.BY_GLOB.containsKey(PlayerDataPaths.ACHIEVEMENTS + "/*.json"));
        assertTrue(RestoreMergers.BY_GLOB.containsKey(PlayerDataPaths.STATS + "/*.json"));
        assertTrue(RestoreMergers.BY_GLOB.containsKey(PlayerDataPaths.NARRATIVE + "/*.json"));
    }
}
