package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.VersionInfo;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Asks the relay which advancements a player holds on trust it has seen them claim cleanly
 * ({@code POST /advancements/vouch}), for {@link games.brennan.dungeontrain.advancement.AdvancementVouch}.
 * The answer is a subset of what was asked; anything else in it is dropped here. Off-thread, one
 * try: it runs again at the next login. An older relay's 404, a failure or a malformed answer is
 * simply nothing.
 */
public final class AdvancementVouchClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private AdvancementVouchClient() {}

    /** {@code confirmed} runs on the HTTP thread with the vouched ids, only when the relay answered some. */
    public static void ask(UUID player, String difficulty, Collection<String> ids, Consumer<List<String>> confirmed) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(DungeonTrain.relayBaseUrl() + "/advancements/vouch"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body(player, difficulty, ids).toString(), StandardCharsets.UTF_8))
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.info("[DungeonTrain] vouch failed: {}", err.toString());
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.info("[DungeonTrain] vouch -> HTTP {}", resp.statusCode());
                                return;
                            }
                            List<String> answer = parse(resp.body(), Set.copyOf(ids));
                            if (!answer.isEmpty()) confirmed.accept(answer);
                        } catch (Throwable t) {
                            LOGGER.info("[DungeonTrain] vouch answer failed: {}", t.toString());
                        }
                    });
        } catch (Throwable t) {
            LOGGER.info("[DungeonTrain] vouch failed to start: {}", t.toString());
        }
    }

    static JsonObject body(UUID player, String difficulty, Collection<String> ids) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", player.toString().replace("-", ""));
        if (difficulty != null && !difficulty.isEmpty()) body.addProperty("difficulty", difficulty);
        body.addProperty("modVersion", VersionInfo.VERSION);
        JsonArray list = new JsonArray();
        ids.forEach(list::add);
        body.add("ids", list);
        return body;
    }

    /** {@code {ok:true, ids:[…]}} → the ids that were asked about; anything else → none. */
    static List<String> parse(String body, Set<String> asked) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return List.of();
        JsonObject o = root.getAsJsonObject();
        if (!o.has("ok") || !o.get("ok").isJsonPrimitive() || !o.get("ok").getAsBoolean()) return List.of();
        if (!o.has("ids") || !o.get("ids").isJsonArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("ids")) {
            if (e.isJsonPrimitive() && asked.contains(e.getAsString()) && !out.contains(e.getAsString())) {
                out.add(e.getAsString());
            }
        }
        return out;
    }
}
