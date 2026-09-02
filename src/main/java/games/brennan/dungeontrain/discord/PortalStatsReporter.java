package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.DeathStatsPacket;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import games.brennan.dungeontrain.portal.PortalConnectionStats;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.Map;

/**
 * POSTs one record per DEATH saying how this life's dimensional carriages went: how many connected,
 * how many did not, and why — so the ratio per version is a number on the relay rather than a
 * report nobody can reproduce.
 *
 * <p>Mirrors {@link DeathReporter}: the same {@link DungeonTrainConfig#isWorldInfoToRelay()} gate,
 * the same no-throw hand-off to the durable {@link RelayOutbox}, fired from
 * {@code RunStatsEvents.onPlayerDeath} inside the same {@link RelayOutbox#runBatched} window so it
 * rides the one {@code POST /telemetry/batch} the other per-death signals share.</p>
 *
 * <p><b>Silent for a life that met no portal.</b> Most lives don't, and a record of two zeros is
 * bytes for nothing. The tally itself costs nothing per tick — see
 * {@link PortalConnectionStats} — so the whole feature is one small POST on the deaths that have
 * something to say, and one INFO line in the local log for the same deaths.</p>
 */
public final class PortalStatsReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String PATH = "/telemetry/portal-stats";

    private static final int TICKS_PER_SECOND = 20;

    private PortalStatsReporter() {}

    /**
     * Take this life's tally and queue it. No-op when disabled, when the life met no portal, or on
     * any error — this must never disrupt death handling.
     *
     * <p>The tally is taken whether or not it is sent: the next life starts from nothing either
     * way.</p>
     */
    public static void report(ServerPlayer player, DeathStatsPacket packet) {
        try {
            PortalConnectionStats.Life life = PortalConnectionStats.takeForLife(player.getUUID());
            if (life.isEmpty()) return;

            LOGGER.info("[DungeonTrain] Portal connections this life: {} connected, {} broke{}",
                life.connected(), life.broken(), describeReasons(life.reasons()));

            if (!DungeonTrainConfig.isWorldInfoToRelay()) return;
            String uuid = player.getUUID().toString().replace("-", "");
            String name = player.getGameProfile().getName();
            long runSec = Math.max(0L, packet.runTicks() / TICKS_PER_SECOND);
            JsonObject payload = buildPayload(uuid, name, WorldJoinReport.modVersion(), runSec,
                packet.cartsTravelled(), life);
            RelayOutbox.get().enqueue(PATH, payload.toString());
            LOGGER.debug("[DungeonTrain] portal stats for {} queued to the relay outbox.", uuid);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] portal stats relay report failed: {}", t.toString());
        }
    }

    /**
     * Pure payload assembly over plain data (no Minecraft types), package-private so the shape can
     * be unit-tested. {@code player} and {@code modVersion} are optional; {@code reasons} is sent
     * only when a breakage happened, and never carries free text — its keys are refusal-reason
     * names.
     */
    static JsonObject buildPayload(String uuid, String player, String modVersion, long runSec,
                                   int carriage, PortalConnectionStats.Life life) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        if (player != null && !player.isEmpty()) body.addProperty("player", player);
        if (modVersion != null && !modVersion.isEmpty()) body.addProperty("modVersion", modVersion);
        body.addProperty("runSec", runSec);
        body.addProperty("carriage", carriage);
        body.addProperty("connected", life.connected());
        body.addProperty("broken", life.broken());
        if (!life.reasons().isEmpty()) {
            JsonObject reasons = new JsonObject();
            for (Map.Entry<String, Integer> entry : life.reasons().entrySet()) {
                reasons.addProperty(entry.getKey(), entry.getValue());
            }
            body.add("reasons", reasons);
        }
        return body;
    }

    /** {@code " (TWIN_NOT_LOADED×1, SEVERED×2)"}, or empty when nothing broke. */
    static String describeReasons(Map<String, Integer> reasons) {
        if (reasons.isEmpty()) return "";
        StringBuilder out = new StringBuilder(" (");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : reasons.entrySet()) {
            if (!first) out.append(", ");
            out.append(entry.getKey()).append('×').append(entry.getValue());
            first = false;
        }
        return out.append(')').toString();
    }
}
