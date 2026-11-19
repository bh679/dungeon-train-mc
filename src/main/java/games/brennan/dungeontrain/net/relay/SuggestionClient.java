package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.CallStatus;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.FetchResult;

import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * Suggested edits: the developer's revision of somebody else's build, held by the relay <em>beside</em>
 * the author's own blocks until the author accepts it into their build or keeps theirs.
 *
 * <p>The relay never lets anyone but the owner (secret) or a leaseholder (token) write a build's
 * blocks, and the developer holds neither for another player's row. So a suggestion goes up through
 * the admin cap into a slot of its own, and only the owner's secret can promote it. Nothing here
 * writes the author's build directly.</p>
 *
 * <p>Wire contract (dp-relay):</p>
 * <ul>
 *   <li>{@code POST <admin>/carriages/<id>/suggest?cap=live|dev} {@code {blocks, dims, sidecars?,
 *       lootPrefabs?, comment, by}} → {@code {ok}}; sets the row's review to {@code edited} with
 *       {@code comment} as its review comment and tells the owner's inbox.</li>
 *   <li>{@code POST /carriages/suggestion} {@code {id, secret}} → the suggestion in
 *       {@code /carriages/fetch}'s shape; 404 when none is pending.</li>
 *   <li>{@code POST /carriages/suggestion/accept|reject} {@code {id, secret}} → {@code {ok}}.</li>
 * </ul>
 */
public final class SuggestionClient {

    private SuggestionClient() {}

    /**
     * Send the developer's revision of build {@code id} to its owner. {@link CallStatus#ERROR} straight
     * away when this install holds no admin URL; {@link CallStatus#UNKNOWN} for a row the relay does not
     * have — or a relay that predates suggestions, which answers the route the same way.
     */
    public static CompletableFuture<CallStatus> adminSuggestEdit(int id, boolean useLive, String blocksBase64,
                                                                 int l, int h, int w, String sidecars,
                                                                 String lootPrefabs, String comment, String by) {
        String admin = RelayTarget.adminSearchBase();
        if (admin.isEmpty()) return CompletableFuture.completedFuture(CallStatus.ERROR);
        return SharedCarriageClient.post(admin, "/carriages/" + id + "/suggest?cap=" + (useLive ? "live" : "dev"),
                        suggestBody(blocksBase64, l, h, w, sidecars, lootPrefabs, comment, by))
                .thenApply(SharedCarriageClient::noteStatus);
    }

    /** The suggest request's body. Package-private for tests. */
    static JsonObject suggestBody(String blocksBase64, int l, int h, int w, String sidecars,
                                  String lootPrefabs, String comment, String by) {
        JsonObject body = new JsonObject();
        body.addProperty("blocks", blocksBase64 == null ? "" : blocksBase64);
        body.add("dims", SharedCarriageClient.dims(l, h, w));
        if (sidecars != null && !sidecars.isEmpty()) body.addProperty("sidecars", sidecars);
        SharedCarriageClient.addLootPrefabs(body, lootPrefabs);
        body.addProperty("comment", comment == null ? "" : comment);
        if (by != null && !by.isBlank()) body.addProperty("by", by);
        return body;
    }

    /** The pending suggestion on the owner's build {@code id}, read like a fetched build. */
    public static CompletableFuture<FetchResult> fetchSuggestion(String baseUrl, int id, String secret) {
        return SharedCarriageClient.post(baseUrl, "/carriages/suggestion", ownerBody(id, secret))
                .thenApply(resp -> resp == null ? FetchResult.failed(CallStatus.ERROR)
                        : SharedCarriageClient.parseFetch(resp));
    }

    /** The owner's answer: {@code accept} makes the suggestion their build, otherwise it is dropped. */
    public static CompletableFuture<CallStatus> decide(String baseUrl, int id, String secret, boolean accept) {
        return SharedCarriageClient.post(baseUrl, "/carriages/suggestion/" + (accept ? "accept" : "reject"),
                        ownerBody(id, secret))
                .thenApply(SuggestionClient::status);
    }

    static JsonObject ownerBody(int id, String secret) {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        body.addProperty("secret", secret == null ? "" : secret);
        return body;
    }

    /** What an owner-side call came back with. Package-private for tests. */
    static CallStatus status(HttpResponse<String> resp) {
        if (resp == null) return CallStatus.ERROR;
        int sc = resp.statusCode();
        if (sc == 403) return CallStatus.FORBIDDEN;
        if (sc == 404) return CallStatus.UNKNOWN;
        return SharedCarriageClient.okJson(resp) != null ? CallStatus.OK : CallStatus.ERROR;
    }
}
