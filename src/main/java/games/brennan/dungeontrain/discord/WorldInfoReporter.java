package games.brennan.dungeontrain.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * POSTs a compact "world info" telemetry record to the Dungeon Train relay on every player join, so the
 * private data explorer's <b>Mods</b> and <b>Seeds</b> cards (dp-relay ≥ 0.17.0) can populate for that
 * game. The record carries enough to identify and reproduce a run:
 * <ul>
 *   <li>the world seed ({@code ServerLevel#getSeed}) and the train generation seed plus its regen inputs
 *       (mode, group size, carriage dims, train Y, starting dimension),</li>
 *   <li>the Dungeon Train version + launcher, and</li>
 *   <li>the full installed-mods list as structured {@code {modId, version}} objects.</li>
 * </ul>
 *
 * <p>Unlike {@link WorldJoinReport} — which formats the same facts as a truncated, spoiler-collapsed
 * Discord string and fires <b>once per world</b> — this reports the complete structured data on
 * <b>every join</b> (no one-shot flag), so each session is attributed. The relay dedupes a record that
 * matches the player's previous one (same seeds + version + mod set), so posting per join is cheap.</p>
 *
 * <p>Invoked from the same {@code joinMessageSuffix} seam as {@link WorldJoinReport} in
 * {@code DungeonTrain}: server thread, already gated by Discord Presence's Discord-enabled +
 * network-consent path. The whole thing is wrapped no-throw and the HTTP POST is fire-and-forget
 * off-thread (mirroring {@code EchoUsageReporter}), so a failed or slow report can never disrupt the
 * join or cost a tick.</p>
 */
public final class WorldInfoReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** A single installed mod as reported to the relay: its id and version string. */
    record ModEntry(String modId, String version) {}

    private WorldInfoReporter() {}

    /**
     * Build and fire the world-info record for the joining player. No-op when disabled, when the
     * server/player can't be resolved, or on any error. Called on the server thread while Discord
     * Presence assembles the join message.
     */
    public static void report(UUID playerId, String playerName) {
        try {
            if (!DungeonTrainConfig.isWorldInfoToRelay()) {
                return;
            }
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return;
            }
            // Confirm the join is a live server player (mirrors WorldJoinReport).
            if (server.getPlayerList().getPlayer(playerId) == null) {
                return;
            }

            var overworld = server.overworld();
            DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
            var cfg = data.getGenerationConfig();
            var dims = data.dims();

            String uuid = playerId.toString().replace("-", "");
            JsonObject payload = buildPayload(
                    uuid,
                    playerName,
                    overworld.getSeed(),
                    cfg.seed(),
                    cfg.mode().name(),
                    cfg.groupSize(),
                    dims.length(), dims.width(), dims.height(),
                    data.getTrainY(),
                    data.startingDimension().name(),
                    WorldJoinReport.modVersion(),
                    LauncherInfo.describe(server.isDedicatedServer()),
                    installedMods());
            post(uuid, payload.toString());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] world-info relay report failed: {}", t.toString());
        }
    }

    /**
     * Pure assembly of the world-info JSON payload — package-private so the shape can be unit-tested
     * without a running server. {@code worldSeed}/{@code trainSeed} are emitted as JSON <b>strings</b>:
     * they are 64-bit longs and JSON numbers would lose precision when the relay parses them in
     * JavaScript. {@code dims} is a nested {@code {l,w,h}} object; {@code mods} is an array of
     * {@code {modId,version}} objects (the caller sorts them for a stable relay-dedupe order).
     */
    static JsonObject buildPayload(String uuid, String player, long worldSeed, long trainSeed,
                                   String mode, int groupSize, int length, int width, int height,
                                   int trainY, String startingDimension, String dtVersion,
                                   String launcher, List<ModEntry> mods) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        body.addProperty("player", player);
        // Seeds as strings — 64-bit longs; JSON numbers lose precision on the relay's JS parse.
        body.addProperty("worldSeed", Long.toString(worldSeed));
        body.addProperty("trainSeed", Long.toString(trainSeed));
        body.addProperty("mode", mode);
        body.addProperty("groupSize", groupSize);
        JsonObject d = new JsonObject();
        d.addProperty("l", length);
        d.addProperty("w", width);
        d.addProperty("h", height);
        body.add("dims", d);
        body.addProperty("trainY", trainY);
        body.addProperty("startingDimension", startingDimension);
        body.addProperty("dtVersion", dtVersion);
        body.addProperty("launcher", launcher);
        JsonArray modsArr = new JsonArray();
        for (ModEntry m : mods) {
            JsonObject mo = new JsonObject();
            mo.addProperty("modId", m.modId());
            mo.addProperty("version", m.version());
            modsArr.add(mo);
        }
        body.add("mods", modsArr);
        return body;
    }

    /**
     * All installed mods as structured {@code {modId, version}} entries, sorted by modId. The sort gives
     * a stable order so the relay — which dedupes a record against the player's previous one by comparing
     * the mod array index-by-index — collapses every unchanged join to a single stored record. Distinct
     * from {@link WorldJoinReport}'s installed-mods list, which returns truncated {@code "modid vX"}
     * display strings for the Discord spoiler.
     */
    static List<ModEntry> installedMods() {
        List<ModEntry> out = new ArrayList<>();
        for (var info : ModList.get().getMods()) {
            out.add(new ModEntry(info.getModId(), info.getVersion().toString()));
        }
        out.sort(Comparator.comparing(ModEntry::modId));
        return out;
    }

    private static void post(String uuid, String json) {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(DungeonTrain.relayBaseUrl() + "/telemetry/world-info"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> LOGGER.debug(
                        "[DungeonTrain] world-info report for {} -> HTTP {}.", uuid, resp.statusCode()))
                .exceptionally(e -> {
                    LOGGER.debug("[DungeonTrain] world-info report for {} failed: {}", uuid, e.toString());
                    return null;
                });
    }
}
