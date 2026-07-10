package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.DeathNarrative;
import games.brennan.dungeontrain.net.DeathStatsPacket;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * POSTs the full paginated death-screen narrative plus the death-screen stat fields not already
 * covered by {@link DeathReporter} ({@code /telemetry/death}, cause/runSec/carriage) or
 * {@link RunSummaryReporter} ({@code /telemetry/run-summary}, runSec/carriage/distanceBlocks) to
 * the Dungeon Train relay, so the private data explorer (dp-relay) can render the same detail a
 * player saw on {@code NarrativeDeathScreen} — narrative + FALL/DEEDS/GEAR/LIVES stats — against a
 * specific death row, not just a dashboard aggregate.
 *
 * <p>Mirrors {@link DeathEquipmentReporter}: same {@link DungeonTrainConfig#isWorldInfoToRelay()}
 * gate reused rather than a new toggle, same no-throw hand-off to the durable {@link RelayOutbox}.
 * Every field here already exists on {@link DeathStatsPacket} — this is a new plain-data payload +
 * POST, no new server-side computation.</p>
 */
public final class DeathDetailReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The subset of {@link DeathStatsPacket}'s numeric fields this reporter sends — plain data
     * (ints/longs/doubles only), so {@link #buildPayload} is unit-testable without bootstrapping
     * ItemStack/PlayerMobAppearance the way constructing a full {@link DeathStatsPacket} would
     * require.
     */
    record DeathStats(
            int mobKills, double damageDealt, double damageTaken,
            int playersEncountered, int playersKilled, int playersBefriended,
            int containersOpened, int booksRead, int booksWritten,
            long lifeDeaths, long lifeCarriages, double lifeDistance, long lifeFriends, long lifeBooks,
            long lifeTrainTicks, long lifeBooksWritten, long lifeContainers, long lifeMobKills,
            long lifePlayersKilled, long lifePlayersEncountered, long lifeEchos, long lifeAdvancements,
            double lifeDamageDealt, double lifeDamageTaken) {

        static DeathStats from(DeathStatsPacket s) {
            return new DeathStats(
                    s.mobKills(), s.damageDealt(), s.damageTaken(),
                    s.playersEncountered(), s.playersKilled(), s.playersBefriended(),
                    s.containersOpened(), s.booksRead(), s.booksWritten(),
                    s.lifeDeaths(), s.lifeCarriages(), s.lifeDistance(), s.lifeFriends(), s.lifeBooks(),
                    s.lifeTrainTicks(), s.lifeBooksWritten(), s.lifeContainers(), s.lifeMobKills(),
                    s.lifePlayersKilled(), s.lifePlayersEncountered(), s.lifeEchos(), s.lifeAdvancements(),
                    s.lifeDamageDealt(), s.lifeDamageTaken());
        }
    }

    private DeathDetailReporter() {}

    /**
     * Build and fire the death-detail record for {@code player} from the death-screen {@code
     * packet}. No-op when disabled or on any error — this must never disrupt death handling.
     */
    public static void report(ServerPlayer player, DeathStatsPacket packet) {
        try {
            if (!DungeonTrainConfig.isWorldInfoToRelay()) {
                return;
            }
            String uuid = player.getUUID().toString().replace("-", "");
            JsonObject payload = buildPayload(uuid, packet.narrative(), DeathStats.from(packet));
            post(uuid, payload.toString());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] death-detail relay report failed: {}", t.toString());
        }
    }

    /**
     * Pure payload assembly over plain data — package-private so the shape can be unit-tested
     * without bootstrapping the game.
     */
    static JsonObject buildPayload(String uuid, DeathNarrative narrative, DeathStats s) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);

        body.addProperty("fallQuestion", narrative.fallQuestion());
        body.addProperty("fallNarration", narrative.fallNarration());
        body.addProperty("deedsQuestion", narrative.deedsQuestion());
        body.addProperty("deedsNarration", narrative.deedsNarration());
        body.addProperty("gearQuestion", narrative.gearQuestion());
        body.addProperty("gearNarration", narrative.gearNarration());
        body.addProperty("livesQuestion", narrative.livesQuestion());
        body.addProperty("livesSubline", narrative.livesSubline());
        body.addProperty("livesNarration", narrative.livesNarration());
        body.addProperty("platformQuestion", narrative.platformQuestion());
        body.addProperty("platformNarration", narrative.platformNarration());
        body.addProperty("platformEpitaph", narrative.platformEpitaph());

        body.addProperty("mobKills", s.mobKills());
        body.addProperty("damageDealt", s.damageDealt());
        body.addProperty("damageTaken", s.damageTaken());
        body.addProperty("playersEncountered", s.playersEncountered());
        body.addProperty("playersKilled", s.playersKilled());
        body.addProperty("playersBefriended", s.playersBefriended());
        body.addProperty("containersOpened", s.containersOpened());
        body.addProperty("booksRead", s.booksRead());
        body.addProperty("booksWritten", s.booksWritten());

        body.addProperty("lifeDeaths", s.lifeDeaths());
        body.addProperty("lifeCarriages", s.lifeCarriages());
        body.addProperty("lifeDistance", s.lifeDistance());
        body.addProperty("lifeFriends", s.lifeFriends());
        body.addProperty("lifeBooks", s.lifeBooks());
        body.addProperty("lifeTrainTicks", s.lifeTrainTicks());
        body.addProperty("lifeBooksWritten", s.lifeBooksWritten());
        body.addProperty("lifeContainers", s.lifeContainers());
        body.addProperty("lifeMobKills", s.lifeMobKills());
        body.addProperty("lifePlayersKilled", s.lifePlayersKilled());
        body.addProperty("lifePlayersEncountered", s.lifePlayersEncountered());
        body.addProperty("lifeEchos", s.lifeEchos());
        body.addProperty("lifeAdvancements", s.lifeAdvancements());
        body.addProperty("lifeDamageDealt", s.lifeDamageDealt());
        body.addProperty("lifeDamageTaken", s.lifeDamageTaken());
        return body;
    }

    private static void post(String uuid, String json) {
        RelayOutbox.get().enqueue("/telemetry/death-detail", json);
        LOGGER.debug("[DungeonTrain] death-detail report for {} queued to the relay outbox.", uuid);
    }
}
