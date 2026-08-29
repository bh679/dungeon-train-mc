package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.net.DeathNarrative;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assembly tests for {@link DeathDetailReporter#buildPayload}. Every narrative field and stat
 * round-trips verbatim into the JSON payload. Pure — no running server or Minecraft bootstrap
 * needed ({@link DeathDetailReporter.DeathStats} is plain data, unlike the full packet).
 */
class DeathDetailReporterTest {

    private static final String UUID = "069a79f444e94726a5befca90e38aaf5";

    private static final DeathNarrative NARRATIVE = new DeathNarrative(
            "fallQ", "fallN", "deedsQ", "deedsN", "gearQ", "gearN",
            "livesQ", "livesSub", "livesN", "platformQ", "platformN", "epitaph");

    private static final DeathDetailReporter.DeathStats STATS = new DeathDetailReporter.DeathStats(
            3, 42.5, 18.0,
            2, 1, 1,
            4, 5, 1,
            7L, 12L, 3400.0, 3L, 9L,
            48000L, 2L, 20L, 55L,
            6L, 30L, 4L, 40L,
            210.5, 190.0);

    private static final DeathDetailReporter.Feats FEATS =
            new DeathDetailReporter.Feats(2, 17L, 31, 24, 480000L);

    @Test
    @DisplayName("all 12 narrative fields round-trip verbatim")
    void narrativeFieldsRoundTrip() {
        JsonObject out = DeathDetailReporter.buildPayload(UUID, NARRATIVE, STATS, FEATS, false);

        assertEquals(UUID, out.get("uuid").getAsString());
        assertEquals("fallQ", out.get("fallQuestion").getAsString());
        assertEquals("fallN", out.get("fallNarration").getAsString());
        assertEquals("deedsQ", out.get("deedsQuestion").getAsString());
        assertEquals("deedsN", out.get("deedsNarration").getAsString());
        assertEquals("gearQ", out.get("gearQuestion").getAsString());
        assertEquals("gearN", out.get("gearNarration").getAsString());
        assertEquals("livesQ", out.get("livesQuestion").getAsString());
        assertEquals("livesSub", out.get("livesSubline").getAsString());
        assertEquals("livesN", out.get("livesNarration").getAsString());
        assertEquals("platformQ", out.get("platformQuestion").getAsString());
        assertEquals("platformN", out.get("platformNarration").getAsString());
        assertEquals("epitaph", out.get("platformEpitaph").getAsString());
    }

    @Test
    @DisplayName("this-run stats round-trip verbatim")
    void thisRunStatsRoundTrip() {
        JsonObject out = DeathDetailReporter.buildPayload(UUID, NARRATIVE, STATS, FEATS, false);

        assertEquals(3, out.get("mobKills").getAsInt());
        assertEquals(42.5, out.get("damageDealt").getAsDouble());
        assertEquals(18.0, out.get("damageTaken").getAsDouble());
        assertEquals(2, out.get("playersEncountered").getAsInt());
        assertEquals(1, out.get("playersKilled").getAsInt());
        assertEquals(1, out.get("playersBefriended").getAsInt());
        assertEquals(4, out.get("containersOpened").getAsInt());
        assertEquals(5, out.get("booksRead").getAsInt());
        assertEquals(1, out.get("booksWritten").getAsInt());
    }

    @Test
    @DisplayName("lifetime totals round-trip verbatim")
    void lifetimeTotalsRoundTrip() {
        JsonObject out = DeathDetailReporter.buildPayload(UUID, NARRATIVE, STATS, FEATS, false);

        assertEquals(7L, out.get("lifeDeaths").getAsLong());
        assertEquals(12L, out.get("lifeCarriages").getAsLong());
        assertEquals(3400.0, out.get("lifeDistance").getAsDouble());
        assertEquals(3L, out.get("lifeFriends").getAsLong());
        assertEquals(9L, out.get("lifeBooks").getAsLong());
        assertEquals(48000L, out.get("lifeTrainTicks").getAsLong());
        assertEquals(2L, out.get("lifeBooksWritten").getAsLong());
        assertEquals(20L, out.get("lifeContainers").getAsLong());
        assertEquals(55L, out.get("lifeMobKills").getAsLong());
        assertEquals(6L, out.get("lifePlayersKilled").getAsLong());
        assertEquals(30L, out.get("lifePlayersEncountered").getAsLong());
        assertEquals(4L, out.get("lifeEchos").getAsLong());
        assertEquals(40L, out.get("lifeAdvancements").getAsLong());
        assertEquals(210.5, out.get("lifeDamageDealt").getAsDouble());
        assertEquals(190.0, out.get("lifeDamageTaken").getAsDouble());
    }

    @Test
    @DisplayName("leaderboard feats round-trip verbatim")
    void leaderboardFeatsRoundTrip() {
        JsonObject out = DeathDetailReporter.buildPayload(UUID, NARRATIVE, STATS, FEATS, false);

        assertEquals(2, out.get("echoesKilled").getAsInt());
        assertEquals(17L, out.get("lifeEchoesKilled").getAsLong());
        assertEquals(31, out.get("maxCarriagesNoChest").getAsInt());
        assertEquals(24, out.get("pacifistCarriages").getAsInt());
        // Displacement, not the lifeDistance odometer that rides alongside it in the same payload.
        assertEquals(480000L, out.get("lifeDistanceTravelled").getAsLong());
    }

    @Test
    @DisplayName("a run with no feats still sends every field, as zero")
    void absentFeatsStillReport() {
        JsonObject out = DeathDetailReporter.buildPayload(UUID, NARRATIVE, STATS, DeathDetailReporter.Feats.NONE, false);

        assertEquals(0, out.get("echoesKilled").getAsInt());
        assertEquals(0L, out.get("lifeEchoesKilled").getAsLong());
        assertEquals(0, out.get("maxCarriagesNoChest").getAsInt());
        assertEquals(0, out.get("pacifistCarriages").getAsInt());
        assertEquals(0L, out.get("lifeDistanceTravelled").getAsLong());
    }

    @Test
    @DisplayName("a Free Play death is flagged, so the relay can keep it off the public boards")
    void freePlayFlagged() {
        JsonObject out = DeathDetailReporter.buildPayload(UUID, NARRATIVE, STATS, FEATS, true);
        assertTrue(out.get("freePlay").getAsBoolean());
        // The record itself is unchanged — the flag withholds the SCORE, not the death.
        assertEquals(FEATS.pacifistCarriages(), out.get("pacifistCarriages").getAsInt());
    }

    @Test
    @DisplayName("a legitimate death carries no freePlay key at all — absent, not false")
    void legitimateDeathOmitsTheFlag() {
        assertFalse(DeathDetailReporter.buildPayload(UUID, NARRATIVE, STATS, FEATS, false).has("freePlay"));
    }
}
