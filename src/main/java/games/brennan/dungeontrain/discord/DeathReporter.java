package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.DeathStatsPacket;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * POSTs a first-class per-DEATH record to the Dungeon Train relay, so the private data explorer
 * (dp-relay) counts <em>every</em> death — not only the ones that produce a Discord death report.
 *
 * <p>The explorer's deaths metric was historically derived from the Discord death-report embed (the
 * relay sniffs COLOR_DEATH off {@code /hook}). That embed is gated behind
 * {@link DungeonTrainConfig#isDeathReportToDiscord()} and is suppressed for Free Play / short-abandon
 * runs, so those deaths were invisible in analytics. This reporter fires on every death, independent
 * of that Discord toggle, giving the relay an authoritative signal (folded into
 * {@code deaths = max(reports, reactions, telemetry)} + an explorer death row).</p>
 *
 * <p>Mirrors {@link RunSummaryReporter}: same relay destination, the same
 * {@link DungeonTrainConfig#isWorldInfoToRelay()} gate (reused rather than a new toggle), and the same
 * no-throw hand-off to the durable {@link RelayOutbox} (persisted, delivered at-least-once on the next
 * flush), fired from {@code RunStatsEvents.onPlayerDeath} — where it shares the outbox
 * {@link RelayOutbox#runBatched} window with the sibling death reporters, so all ~5 per-death signals
 * coalesce into a single {@code POST /telemetry/batch} rather than one request each. The payload
 * carries only the death cause (the same second-person text the death screen shows) plus this life's
 * duration + carriage — never free-text beyond that.</p>
 */
public final class DeathReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int TICKS_PER_SECOND = 20;

    private DeathReporter() {}

    /**
     * Build and queue the per-death record for {@code player} from the death-screen {@code packet}.
     * No-op when disabled or on any error — this must never disrupt death handling.
     */
    public static void report(ServerPlayer player, DeathStatsPacket packet) {
        try {
            if (!DungeonTrainConfig.isWorldInfoToRelay()) {
                return;
            }
            String uuid = player.getUUID().toString().replace("-", "");
            String name = player.getGameProfile().getName();
            long runSec = Math.max(0L, packet.runTicks() / TICKS_PER_SECOND);
            int carriage = packet.cartsTravelled();
            JsonObject payload = buildPayload(uuid, name, packet.deathCause(), runSec, carriage);
            post(uuid, payload.toString());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] death relay report failed: {}", t.toString());
        }
    }

    /**
     * Pure payload assembly over plain data (no Minecraft types) — package-private so the shape can
     * be unit-tested without bootstrapping the game. {@code cause} is the second-person death message
     * ({@code packet.deathCause()}); {@code runSec} is the life's elapsed seconds ({@code runTicks /
     * 20}); {@code carriage} is the carriage reached. {@code player} + {@code cause} are optional.
     */
    static JsonObject buildPayload(String uuid, String player, String cause, long runSec, int carriage) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        if (player != null && !player.isEmpty()) {
            body.addProperty("player", player);
        }
        if (cause != null && !cause.isEmpty()) {
            body.addProperty("cause", cause);
        }
        body.addProperty("runSec", runSec);
        body.addProperty("carriage", carriage);
        return body;
    }

    private static void post(String uuid, String json) {
        RelayOutbox.get().enqueue("/telemetry/death", json);
        LOGGER.debug("[DungeonTrain] death report for {} queued to the relay outbox.", uuid);
    }
}
