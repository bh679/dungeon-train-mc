package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.DeathStatsPacket;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import org.slf4j.Logger;

/**
 * POSTs a per-LIFE run summary (spawn → death) to the Dungeon Train relay, so the private data
 * explorer (dp-relay) can show a player's <em>single-life</em> time on the train — the same clock the
 * death report prints as {@code H:MM:SS}. A "life" ends at each death
 * ({@code PlayerRunState.trainTimeTicks} resets on respawn), so one record is posted per death.
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
     *
     * <p>{@code pos} is resolved once by the caller and shared with the sibling reporters and the
     * lifetime displacement counter, so no two of them can describe the same death differently.
     * Its {@code distanceTravelled} is what the public distance leaderboard is built from.</p>
     */
    public static void report(ServerPlayer player, DeathStatsPacket packet, RunPosition pos,
                              boolean freePlay) {
        try {
            if (!DungeonTrainConfig.isWorldInfoToRelay()) {
                return;
            }
            String uuid = player.getUUID().toString().replace("-", "");
            String name = player.getGameProfile().getName();
            long runSec = Math.max(0L, packet.trainTimeTicks() / TICKS_PER_SECOND);
            int carriage = packet.cartsTravelled();
            int distanceBlocks = (int) Math.round(packet.distanceBlocks());
            JsonObject payload = buildPayload(uuid, name, runSec, carriage, distanceBlocks, pos, freePlay,
                    WorldJoinReport.modVersion(), lifeSec(player));
            post(uuid, payload.toString());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] run-summary relay report failed: {}", t.toString());
        }
    }

    /**
     * Pure payload assembly over plain data (no Minecraft types) — package-private so the shape can
     * be unit-tested without bootstrapping the game. {@code runSec} is the life's time on the train
     * in seconds ({@code trainTimeTicks / 20}); {@code carriage} + {@code distanceBlocks} are cheap
     * extras.
     *
     * <p>{@code distanceBlocks} keeps its long-standing meaning — the 3D path-length odometer. The
     * {@link RunPosition} fields are the newer positional metric and are independent of it; both
     * ship so the relay can fall back to the odometer for lives predating the origin capture.</p>
     */
    static JsonObject buildPayload(String uuid, String player, long runSec, int carriage, int distanceBlocks,
                                   RunPosition pos, boolean freePlay) {
        return buildPayload(uuid, player, runSec, carriage, distanceBlocks, pos, freePlay, null);
    }

    /**
     * As above, with the Dungeon Train version this client runs. The relay cuts the one-life boards
     * into ERAS by version (a balancing release opens a new one), and this field is what places a run
     * in its era — absent, the relay files it under the oldest era there is. Sent only when known.
     */
    static JsonObject buildPayload(String uuid, String player, long runSec, int carriage, int distanceBlocks,
                                   RunPosition pos, boolean freePlay, String modVersion) {
        return buildPayload(uuid, player, runSec, carriage, distanceBlocks, pos, freePlay, modVersion, -1L);
    }

    /**
     * As above, with the life's WALL-CLOCK length. {@code runSec} is active time on the train — it
     * stops while the player is paused or idle, and the train keeps moving — so the relay cannot bound
     * distance by it. {@code lifeSec} is vanilla's time-since-death stat in seconds: every server tick
     * the player was alive, AFK included, which is exactly what the train's 2 blocks/s is measured
     * against. The relay's plausibility checks read it as {@code lifeSec}; negative means unknown
     * and the field is omitted.
     */
    static JsonObject buildPayload(String uuid, String player, long runSec, int carriage, int distanceBlocks,
                                   RunPosition pos, boolean freePlay, String modVersion, long lifeSec) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        if (player != null && !player.isEmpty()) {
            body.addProperty("player", player);
        }
        body.addProperty("runSec", runSec);
        body.addProperty("carriage", carriage);
        body.addProperty("distanceBlocks", distanceBlocks);
        DeathReporter.addPosition(body, pos);
        // Was this life Free Play? Same flag, same reason, same ALWAYS-sent rule as
        // DeathDetailReporter — see the note there. This is the payload the one-life distance,
        // playtime and carriage boards are built from, so it is the one that was showing
        // world-border "distances" before the flag existed.
        body.addProperty("freePlay", freePlay);
        addModVersion(body, modVersion);
        if (lifeSec >= 0L) {
            body.addProperty("lifeSec", lifeSec);
        }
        return body;
    }

    /**
     * Seconds this player has been alive, from vanilla's {@code TIME_SINCE_DEATH} stat. Read at the
     * death event, which NeoForge fires at the top of {@code ServerPlayer.die()} — before vanilla resets
     * the stat for the next life. {@code -1} when the stat cannot be read, so the field is simply absent
     * rather than wrong.
     */
    static long lifeSec(ServerPlayer player) {
        try {
            int ticks = player.getStats().getValue(Stats.CUSTOM.get(Stats.TIME_SINCE_DEATH));
            return Math.max(0L, ticks / TICKS_PER_SECOND);
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** The version the relay places a one-life score by, when this jar knows its own. Shared with the death payload. */
    static void addModVersion(JsonObject body, String modVersion) {
        if (modVersion != null && !modVersion.isBlank() && !"unknown".equals(modVersion)) {
            body.addProperty("modVersion", modVersion.trim());
        }
    }

    private static void post(String uuid, String json) {
        RelayOutbox.get().enqueue("/telemetry/run-summary", json);
        LOGGER.debug("[DungeonTrain] run-summary report for {} queued to the relay outbox.", uuid);
    }
}
