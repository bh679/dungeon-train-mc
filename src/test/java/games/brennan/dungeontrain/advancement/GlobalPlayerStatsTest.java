package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The on-disk migration path for {@link GlobalPlayerStats}.
 *
 * <p>Counters here move into nested records as the top-level {@code group(...)} runs out of room —
 * {@code totalEchos} did it once, and {@code totalDistance} / {@code distanceBlocks} have now done
 * it to make space for lifetime displacement. Every one of those moves is a silent-data-loss bug if
 * it is wrong: the codec would find no field, read the default, and write a zeroed file back over a
 * player's whole history — no exception, no log line, just a lifetime of metres gone. So the
 * migration is pinned here rather than trusted.</p>
 */
class GlobalPlayerStatsTest {

    /** Exactly what a stat file written before either nesting looks like. */
    private static final String LEGACY = """
        {"trainTicks":864000,"randomBooksRead":12,"totalDeaths":40,"totalCarriages":9001,
         "totalDistance":123456.5,"distanceBlocks":987654.25,"totalEchos":17}""";

    private static GlobalPlayerStats.Data parse(String json) {
        JsonElement migrated = GlobalPlayerStats.migrateLegacy(JsonParser.parseString(json));
        var result = GlobalPlayerStats.Data.CODEC.parse(JsonOps.INSTANCE, migrated);
        assertTrue(result.error().isEmpty(), () -> "parse failed: " + result.error());
        return result.result().orElseThrow();
    }

    @Test
    @DisplayName("a pre-nesting file keeps every metre and every echo it had")
    void legacyFileSurvives() {
        GlobalPlayerStats.Data data = parse(LEGACY);
        assertEquals(123456.5, data.totalDistance());
        assertEquals(987654.25, data.distanceBlocks());
        assertEquals(17L, data.totalEchos());
        // Untouched top-level fields still land where they always did.
        assertEquals(864000L, data.trainTicks());
        assertEquals(9001L, data.totalCarriages());
        // Nothing was ever counting displacement, so it starts at zero rather than borrowing
        // a number from either odometer.
        assertEquals(0.0, data.totalDisplacement());
    }

    @Test
    @DisplayName("a migrated file is left alone — the stale top-level copy cannot overwrite it")
    void migrationIsIdempotent() {
        // Gson keeps the old keys alongside the new object, so re-running must not rebuild from them.
        JsonElement once = GlobalPlayerStats.migrateLegacy(JsonParser.parseString(LEGACY));
        once.getAsJsonObject().getAsJsonObject("distance").addProperty("displacement", 4200.0);
        once.getAsJsonObject().getAsJsonObject("distance").addProperty("runs", 5.0);

        JsonElement twice = GlobalPlayerStats.migrateLegacy(once);
        JsonObject distance = twice.getAsJsonObject().getAsJsonObject("distance");
        assertEquals(5.0, distance.get("runs").getAsDouble());
        assertEquals(4200.0, distance.get("displacement").getAsDouble());
    }

    @Test
    @DisplayName("a file already carrying the nested shape round-trips unchanged")
    void nestedFileRoundTrips() {
        GlobalPlayerStats.Data before = GlobalPlayerStats.Data.EMPTY
                .plusDistance(10.0).plusDistanceBlocks(20.0).plusDisplacement(30.0)
                .plusEchoesKilled(3L).plusDamageDealt(1.5);
        JsonElement encoded = GlobalPlayerStats.Data.CODEC
                .encodeStart(JsonOps.INSTANCE, before).result().orElseThrow();

        GlobalPlayerStats.Data after = parse(encoded.toString());
        assertEquals(10.0, after.totalDistance());
        assertEquals(20.0, after.distanceBlocks());
        assertEquals(30.0, after.totalDisplacement());
        assertEquals(3L, after.totalEchoesKilled());
        assertEquals(1.5, after.totalDamageDealt());
    }

    @Test
    @DisplayName("an empty object reads as a fresh player, not a parse failure")
    void emptyObjectIsAFreshPlayer() {
        assertEquals(GlobalPlayerStats.Data.EMPTY, parse("{}"));
    }

    @Test
    @DisplayName("a file with no building object reads zero and keeps everything else")
    void buildingDefaultsToZero() {
        GlobalPlayerStats.Data data = parse(LEGACY);
        assertEquals(0L, data.builderTicks());
        assertEquals(0L, data.editorTicks());
        assertEquals(0L, data.buildingTicks());
        assertEquals(864000L, data.trainTicks());
        assertEquals(17L, data.totalEchos());
    }

    @Test
    @DisplayName("builder and editor ticks round-trip, and the board reads their sum")
    void buildingRoundTrips() {
        GlobalPlayerStats.Data before = GlobalPlayerStats.Data.EMPTY
                .plusBuilderTicks(72_000L)
                .plusEditorTicks(24_000L);
        JsonElement json = GlobalPlayerStats.Data.CODEC.encodeStart(JsonOps.INSTANCE, before)
                .result().orElseThrow();
        GlobalPlayerStats.Data after = parse(json.toString());
        assertEquals(72_000L, after.builderTicks());
        assertEquals(24_000L, after.editorTicks());
        assertEquals(96_000L, after.buildingTicks());
    }
}
