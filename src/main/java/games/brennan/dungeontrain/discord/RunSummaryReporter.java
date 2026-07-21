package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.DeathStatsPacket;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * POSTs a per-LIFE run summary (spawn → death) to the Dungeon Train relay, so the private data
 * explorer (dp-relay) can show a player's <em>single-life</em> playtime — the same run timer the
 * death report prints as {@code H:MM:SS}. A "life" ends at each death ({@code PlayerRunState.runTicks}
 * resets on respawn), so one record is posted per death.
 *
 * <p>Mirrors {@link DeathEquipmentReporter}: same relay destination, the same
 * {@link DungeonTrainConfig#isWorldInfoToRelay()} gate (reused rather than a new toggle), and the same
 * no-throw hand-off to the durable {@link RelayOutbox} (persisted, delivered at-least-once on the next
 * flush), fired from {@code RunStatsEvents.onPlayerDeath}. The run duration is authoritative; the relay
 * also parses it out of the Discord death embed as a fallback for builds that don't POST this, so
 * shipping it is additive.</p>
 */
public final class RunSummaryReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int TICKS_PER_SECOND = 20;

    private RunSummaryReporter() {}

    /**
     * Build and fire the run-summary record for {@code player} from the death-screen {@code packet}.
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
            int distanceBlocks = (int) Math.round(packet.distanceBlocks());
            RunPosition pos = RunPosition.of(player);
            JsonObject payload = buildPayload(uuid, name, runSec, carriage, distanceBlocks, pos);
            post(uuid, payload.toString());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] run-summary relay report failed: {}", t.toString());
        }
    }

    /**
     * Pure payload assembly over plain data (no Minecraft types) — package-private so the shape can
     * be unit-tested without bootstrapping the game. {@code runSec} is the life's elapsed seconds
     * ({@code runTicks / 20}); {@code carriage} + {@code distanceBlocks} are cheap extras.
     *
     * <p>{@code distanceBlocks} keeps its long-standing meaning — the 3D path-length odometer. The
     * {@link RunPosition} fields are the newer positional metric and are independent of it; both
     * ship so the relay can fall back to the odometer for lives predating the origin capture.</p>
     */
    static JsonObject buildPayload(String uuid, String player, long runSec, int carriage, int distanceBlocks,
                                   RunPosition pos) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        if (player != null && !player.isEmpty()) {
            body.addProperty("player", player);
        }
        body.addProperty("runSec", runSec);
        body.addProperty("carriage", carriage);
        body.addProperty("distanceBlocks", distanceBlocks);
        DeathReporter.addPosition(body, pos);
        return body;
    }

    private static void post(String uuid, String json) {
        RelayOutbox.get().enqueue("/telemetry/run-summary", json);
        LOGGER.debug("[DungeonTrain] run-summary report for {} queued to the relay outbox.", uuid);
    }
}
