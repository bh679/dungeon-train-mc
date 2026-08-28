package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * POSTs a player's lifetime <em>building</em> time to the Dungeon Train relay — the seconds behind
 * the public "Longest Building" leaderboard.
 *
 * <p>It exists because every other board is fed by a death or a run summary, and building produces
 * neither: a player can spend fifty hours in the Train Builder without ever dying, so nothing they
 * did would otherwise reach the relay at all. The counter itself is
 * {@code GlobalPlayerStats.buildingTicks} — time in a builder world plus time up at the editor
 * plots, accrued by {@code BuildingTimeEvents}.
 *
 * <p>Mirrors {@link RunSummaryReporter}: same relay destination, the same
 * {@link DungeonTrainConfig#isWorldInfoToRelay()} gate, and the same no-throw hand-off to the
 * durable {@link RelayOutbox}. The score posted is the LIFETIME total rather than a delta, and the
 * relay folds scores with {@code MAX(...)} — so a duplicate, a retry, or two posts arriving out of
 * order all settle on the same number, and a dropped post costs nothing but freshness.</p>
 */
public final class BuilderTimeReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int TICKS_PER_SECOND = 20;

    private BuilderTimeReporter() {}

    /**
     * Fire the builder-time record for {@code player} from a lifetime tick total. No-op when
     * disabled, when nothing has been built yet, or on any error — this runs off a tick and off
     * logout, and must never disrupt either.
     */
    public static void report(ServerPlayer player, long buildingTicks) {
        try {
            if (player == null || !DungeonTrainConfig.isWorldInfoToRelay()) {
                return;
            }
            long builderSec = Math.max(0L, buildingTicks / TICKS_PER_SECOND);
            if (builderSec <= 0L) {
                return;
            }
            String uuid = player.getUUID().toString().replace("-", "");
            String name = player.getGameProfile().getName();
            post(uuid, buildPayload(uuid, name, builderSec).toString());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] builder-time relay report failed: {}", t.toString());
        }
    }

    /**
     * Pure payload assembly over plain data (no Minecraft types) — package-private so the wire shape
     * can be unit-tested without bootstrapping the game. {@code builderSec} is the lifetime total,
     * not this session's share of it.
     */
    static JsonObject buildPayload(String uuid, String player, long builderSec) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        if (player != null && !player.isEmpty()) {
            body.addProperty("player", player);
        }
        body.addProperty("builderSec", builderSec);
        return body;
    }

    private static void post(String uuid, String json) {
        RelayOutbox.get().enqueue("/telemetry/builder-time", json);
        LOGGER.debug("[DungeonTrain] builder-time report for {} queued to the relay outbox.", uuid);
    }
}
