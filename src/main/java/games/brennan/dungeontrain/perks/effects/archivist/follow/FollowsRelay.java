package games.brennan.dungeontrain.perks.effects.archivist.follow;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.narrative.BookSafeText;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Off-thread client for the relay's {@code /follows} endpoints (Favourite Author). Every call answers
 * its callback exactly once, off the server thread; the caller hops back.
 */
final class FollowsRelay {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1) // see BookAuthorsClient: h2c breaks local testing
        .connectTimeout(Duration.ofSeconds(8))
        .build();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** What an add asked for: a player name, a community book id, or a community photo id. */
    enum By { NAME, BOOK, PHOTO }

    /** An add's answer: found (with the follow), not found, or the relay could not be asked. */
    record AddResult(boolean answered, Optional<AuthorFollows.Follow> follow) {
        static AddResult failed() { return new AddResult(false, Optional.empty()); }
    }

    private FollowsRelay() {}

    static String bare(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    /** The list, or empty when the relay could not be asked ({@code Optional.empty()}). */
    static void list(UUID player, Consumer<Optional<List<AuthorFollows.Follow>>> callback) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(DungeonTrain.relayBaseUrl() + "/follows?uuid=" + bare(player)))
            .timeout(TIMEOUT).header("Accept", "application/json").GET().build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
            Optional<List<AuthorFollows.Follow>> out = Optional.empty();
            try {
                if (err == null && resp.statusCode() == 200) out = parseList(resp.body());
                else LOGGER.debug("[DungeonTrain] follows list failed: {}", err != null ? err.toString() : resp.statusCode());
            } catch (RuntimeException e) {
                LOGGER.debug("[DungeonTrain] follows list unreadable: {}", e.toString());
            }
            callback.accept(out);
        });
    }

    static void add(UUID player, By by, String value, Consumer<AddResult> callback) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", bare(player));
        switch (by) {
            case NAME -> body.addProperty("name", value);
            case BOOK -> body.addProperty("bookId", Integer.parseInt(value));
            case PHOTO -> body.addProperty("photoId", Integer.parseInt(value));
        }
        post("/follows/add", body, respBody -> {
            if (respBody == null) return AddResult.failed();
            JsonObject o = JsonParser.parseString(respBody).getAsJsonObject();
            boolean found = o.has("found") && o.get("found").getAsBoolean();
            if (!found || !o.has("follow") || !o.get("follow").isJsonObject()) return new AddResult(true, Optional.empty());
            return new AddResult(true, Optional.ofNullable(parseFollow(o.getAsJsonObject("follow"))));
        }, AddResult.failed(), callback);
    }

    static void remove(UUID player, String token, Consumer<Boolean> callback) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", bare(player));
        body.addProperty("token", token);
        post("/follows/remove", body, respBody -> respBody != null, false, callback);
    }

    private static <T> void post(String path, JsonObject body, java.util.function.Function<String, T> read, T failed,
                                 Consumer<T> callback) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(DungeonTrain.relayBaseUrl() + path))
                .timeout(TIMEOUT).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
                T out = failed;
                try {
                    if (err == null && resp.statusCode() == 200) out = read.apply(resp.body());
                    else LOGGER.debug("[DungeonTrain] {} failed: {}", path, err != null ? err.toString() : resp.statusCode());
                } catch (RuntimeException e) {
                    LOGGER.debug("[DungeonTrain] {} unreadable: {}", path, e.toString());
                }
                callback.accept(out);
            });
        } catch (RuntimeException e) {
            LOGGER.debug("[DungeonTrain] {} failed to start: {}", path, e.toString());
            callback.accept(failed);
        }
    }

    /** {@code {ok, follows:[…]}} → the follows; empty Optional for anything unreadable. */
    static Optional<List<AuthorFollows.Follow>> parseList(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return Optional.empty();
        JsonObject o = root.getAsJsonObject();
        if (!o.has("follows") || !o.get("follows").isJsonArray()) return Optional.empty();
        List<AuthorFollows.Follow> out = new ArrayList<>();
        for (JsonElement el : o.getAsJsonArray("follows")) {
            if (!el.isJsonObject()) continue;
            AuthorFollows.Follow f = parseFollow(el.getAsJsonObject());
            if (f != null) out.add(f);
        }
        return Optional.of(List.copyOf(out));
    }

    static AuthorFollows.Follow parseFollow(JsonObject o) {
        if (!o.has("token") || o.get("token").isJsonNull()) return null;
        String token = o.get("token").getAsString();
        if (token.isBlank()) return null;
        String name = o.has("name") && !o.get("name").isJsonNull() ? BookSafeText.sanitizeName(o.get("name").getAsString()) : "";
        long ts = o.has("ts") && o.get("ts").isJsonPrimitive() ? o.get("ts").getAsLong() : 0L;
        boolean exhausted = o.has("exhausted") && o.get("exhausted").isJsonPrimitive() && o.get("exhausted").getAsBoolean();
        return new AuthorFollows.Follow(token, name, ts, exhausted);
    }
}
