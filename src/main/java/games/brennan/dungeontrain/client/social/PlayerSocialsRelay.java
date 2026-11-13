package games.brennan.dungeontrain.client.social;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.client.OwnerProofJoin;
import games.brennan.dungeontrain.net.relay.RelayTarget;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The relay half of My socials (relay {@code player-socials-web.js}): the Instagram account a player added to their
 * profile, so the official posts that feature their content can tag them.
 *
 * <ul>
 *   <li>{@code POST /player-socials {uuid}} lists it, with whether the relay offers the DM code.</li>
 *   <li>{@code POST /player-socials/dm-code {uuid, name, serverId}} hands back a code ({@code DT-7K2QH}) the player DMs
 *       to @dungeontrain from their Instagram; the relay reads who sent it (relay {@code player-socials-dm.js}), so the
 *       handle is proven to be the player's. Any Instagram account works.</li>
 *   <li>{@code POST /player-socials/remove|tag} change one platform.</li>
 * </ul>
 *
 * <p>Every change carries a Minecraft-session proof ({@code /carriages/owner-proof} then a session join, the way My
 * Builds proves an owner), so nobody can edit another player's socials. An offline or dev account cannot prove
 * itself, and is told to use the website. Nothing is sent without the internet connection consent. All calls resolve
 * off-thread and never throw.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PlayerSocialsRelay {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    static final String LIST_PATH = "/player-socials";
    static final String REMOVE_PATH = "/player-socials/remove";
    static final String TAG_PATH = "/player-socials/tag";
    static final String DM_CODE_PATH = "/player-socials/dm-code";
    static final Set<String> PLATFORMS = Set.of("instagram");
    /** The way to add an account: a code DMed to @dungeontrain. */
    public static final String INSTAGRAM_DM = "instagram_dm";
    static final Set<String> SIGN_INS = Set.of(INSTAGRAM_DM);
    /** The rows My Socials shows, in order. */
    public static final List<String> PLATFORM_ORDER = List.of("instagram");
    /** A proven serverId keeps proving on the relay for 10 minutes; reuse it a little less than that. */
    private static final long PROOF_REUSE_MS = 9 * 60 * 1000L;

    /** One account on the profile. {@code via} is the sign-in that proved it. */
    public record Social(String platform, String handle, boolean tag, String via) {}

    /** A code waiting to be DMed to @dungeontrain: what to send, until when (epoch ms), and the DM link to open. */
    public record DmCode(String code, long expiresAt, String dmUrl) {
        public boolean live() {
            return expiresAt > System.currentTimeMillis();
        }
    }

    /**
     * The profile: its accounts, the sign-ins on offer ({@code instagram_dm} once the relay has it switched on), the
     * site page, and a DM code still waiting, if any.
     */
    public record Status(List<Social> socials, List<String> signIns, @Nullable String page, @Nullable DmCode pendingCode) {
        public Status {
            socials = List.copyOf(socials);
            signIns = List.copyOf(signIns);
        }
    }

    /** Why a change did not happen. */
    public enum Failure { NO_CONSENT, NOT_PROVEN, UNAVAILABLE, FAILED }

    /** A change's outcome: the profile afterwards (or a DM code), or why not. */
    public record Result(@Nullable Status status, @Nullable DmCode code, @Nullable Failure failure) {
        static Result failed(Failure f) {
            return new Result(null, null, f);
        }
    }

    private static volatile String proofServerId = "";
    private static volatile long proofTs;

    private PlayerSocialsRelay() {}

    /** The player's socials; completes with null on any failure or without consent. */
    public static CompletableFuture<Status> fetch() {
        String uuid = ownUuid();
        if (uuid == null) return CompletableFuture.completedFuture(null);
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        return post(LIST_PATH, body).thenApply(r -> r != null && r.statusCode() == 200 ? parseStatus(r.body()) : null);
    }

    /** A code to DM to @dungeontrain from the player's Instagram, which adds that Instagram. */
    public static CompletableFuture<Result> dmCode() {
        return proven(body -> {}, DM_CODE_PATH);
    }

    /** Take one platform off the profile. */
    public static CompletableFuture<Result> remove(String platform) {
        return proven(body -> body.addProperty("platform", platform), REMOVE_PATH);
    }

    /** Turn tagging on one platform on or off. */
    public static CompletableFuture<Result> setTag(String platform, boolean on) {
        return proven(body -> {
            body.addProperty("platform", platform);
            body.addProperty("on", on);
        }, TAG_PATH);
    }

    private static CompletableFuture<Result> proven(java.util.function.Consumer<JsonObject> fill, String path) {
        try {
            String uuid = ownUuid();
            if (uuid == null) return CompletableFuture.completedFuture(Result.failed(Failure.NO_CONSENT));
            User user = Minecraft.getInstance().getUser();
            if (user.getType() != User.Type.MSA) return CompletableFuture.completedFuture(Result.failed(Failure.NOT_PROVEN));
            UUID id = user.getProfileId();
            String name = user.getName();
            return proof(id).thenCompose(serverId -> {
                if (serverId.isEmpty()) return CompletableFuture.completedFuture(Result.failed(Failure.NOT_PROVEN));
                JsonObject body = new JsonObject();
                body.addProperty("uuid", uuid);
                body.addProperty("name", name);
                body.addProperty("serverId", serverId);
                fill.accept(body);
                return post(path, body).thenApply(r -> {
                    if (r == null) return Result.failed(Failure.FAILED);
                    if (r.statusCode() == 403) {
                        proofServerId = ""; // expired on the relay: prove afresh next time
                        return Result.failed(Failure.NOT_PROVEN);
                    }
                    if (r.statusCode() == 503 || r.statusCode() == 404) return Result.failed(Failure.UNAVAILABLE);
                    if (r.statusCode() != 200) return Result.failed(Failure.FAILED);
                    DmCode code = parseDmCode(r.body());
                    if (code != null) return new Result(null, code, null);
                    return new Result(parseStatus(r.body()), null, null);
                });
            }).exceptionally(t -> {
                LOGGER.debug("[DungeonTrain] player-socials {} failed: {}", path, t.toString());
                return Result.failed(Failure.FAILED);
            });
        } catch (RuntimeException e) {
            LOGGER.debug("[DungeonTrain] player-socials {} failed: {}", path, e.toString());
            return CompletableFuture.completedFuture(Result.failed(Failure.FAILED));
        }
    }

    /** A proven serverId: the last one while it still proves, else a fresh challenge and session join. "" on failure. */
    private static CompletableFuture<String> proof(UUID id) {
        if (!proofServerId.isEmpty() && System.currentTimeMillis() - proofTs < PROOF_REUSE_MS) {
            return CompletableFuture.completedFuture(proofServerId);
        }
        return SharedCarriageClient.ownerProofChallenge(id.toString(), RelayTarget.live())
            .thenApplyAsync(serverId -> {
                if (serverId == null || serverId.isEmpty() || !OwnerProofJoin.join(id, serverId)) return "";
                proofServerId = serverId;
                proofTs = System.currentTimeMillis();
                return serverId;
            })
            .exceptionally(t -> "");
    }

    /** The {@code /player-socials} (or a change's) body as a Status; unknown platforms and bad rows dropped. Pure. */
    @Nullable
    static Status parseStatus(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return null;
            JsonObject o = root.getAsJsonObject();
            List<Social> socials = new ArrayList<>();
            if (o.has("socials") && o.get("socials").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("socials")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject s = e.getAsJsonObject();
                    String platform = str(s, "platform");
                    String handle = str(s, "handle");
                    if (platform == null || !PLATFORMS.contains(platform) || handle == null || !handle.matches("[A-Za-z0-9._]{1,30}")) continue;
                    boolean tag = s.has("tag") && s.get("tag").isJsonPrimitive() && s.get("tag").getAsBoolean();
                    String via = str(s, "via");
                    socials.add(new Social(platform, handle, tag, via == null ? platform : via));
                }
            }
            List<String> signIns = new ArrayList<>();
            if (o.has("signIns") && o.get("signIns").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("signIns")) {
                    if (e.isJsonPrimitive() && SIGN_INS.contains(e.getAsString())) signIns.add(e.getAsString());
                }
            }
            String page = str(o, "page");
            DmCode pending = o.has("pendingCode") && o.get("pendingCode").isJsonObject() ? dmCode(o.getAsJsonObject("pendingCode")) : null;
            return new Status(socials, signIns, page != null && page.startsWith("https://") ? page : null, pending);
        } catch (RuntimeException ex) {
            LOGGER.debug("[DungeonTrain] player-socials body unreadable: {}", ex.toString());
            return null;
        }
    }

    /** The sign-in a platform's row uses, from those the relay offers, or null: Instagram's DM code. Pure. */
    @Nullable
    public static String signInFor(String platform, List<String> signIns) {
        return "instagram".equals(platform) && signIns.contains(INSTAGRAM_DM) ? INSTAGRAM_DM : null;
    }

    /** A {@code /dm-code} answer as a DmCode, or null. Pure. */
    @Nullable
    static DmCode parseDmCode(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            return root.isJsonObject() ? dmCode(root.getAsJsonObject()) : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** {@code {code, expiresAt, dmUrl}} as a DmCode, or null when any part is off. */
    @Nullable
    private static DmCode dmCode(JsonObject o) {
        String code = str(o, "code");
        String url = str(o, "dmUrl");
        JsonElement exp = o.get("expiresAt");
        if (code == null || !code.matches("DT-[A-Z2-9]{5}") || url == null || !url.startsWith("https://ig.me/")
                || exp == null || !exp.isJsonPrimitive() || !exp.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return new DmCode(code, exp.getAsLong(), url);
    }

    /** The public profile page of {@code handle} on {@code platform}, or null for an unknown platform. Pure. */
    @Nullable
    public static String profileUrl(String platform, String handle) {
        if (handle == null || !handle.matches("[A-Za-z0-9._]{1,30}")) return null;
        return switch (platform) {
            case "instagram" -> "https://www.instagram.com/" + handle + "/";
            default -> null;
        };
    }

    @Nullable
    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return null;
        String s = e.getAsString();
        return s.isBlank() ? null : s;
    }

    private static CompletableFuture<HttpResponse<String>> post(String path, JsonObject body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(RelayTarget.live() + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
            return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> {
                    if (r.statusCode() / 100 != 2) LOGGER.debug("[DungeonTrain] player-socials {} answered {}", path, r.statusCode());
                    return r;
                })
                .exceptionally(t -> {
                    LOGGER.debug("[DungeonTrain] player-socials {} failed: {}", path, t.toString());
                    return null;
                });
        } catch (RuntimeException e) {
            LOGGER.debug("[DungeonTrain] player-socials {} failed: {}", path, e.toString());
            return CompletableFuture.completedFuture(null);
        }
    }

    /** This player's uuid without dashes, or null without the internet connection consent. */
    @Nullable
    private static String ownUuid() {
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc != null && mc.getUser() != null ? mc.getUser().getProfileId() : null;
        if (uuid == null || !DiscordPresenceClientConfig.isGranted()) return null;
        return uuid.toString().replace("-", "");
    }
}
