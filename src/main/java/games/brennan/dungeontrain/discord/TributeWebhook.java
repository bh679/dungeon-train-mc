package games.brennan.dungeontrain.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * A player's own Discord webhook — the Tribute webhook on the Options Account tab. Every photo the
 * player pays Tribute to is also posted there ({@link TributePhotoReporter#postPersonal}).
 *
 * <p>The link is a secret (anyone holding it can post to that channel), and on a multiplayer server it
 * is a URL the <i>server</i> fetches on a player's say-so. So: {@link #isValid} accepts a Discord webhook
 * link and nothing else, the server re-checks it on receipt, and nothing here ever logs it. Posts are
 * best-effort and never retried or queued to disk.</p>
 *
 * <p>No Minecraft types: shared by the client (Send test) and the server (the Tribute post).</p>
 */
public final class TributeWebhook {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Longest link accepted, in the Options field and on the wire. Real ones are about 120. */
    public static final int MAX_LENGTH = 256;

    private static final Set<String> HOSTS =
            Set.of("discord.com", "discordapp.com", "ptb.discord.com", "canary.discord.com");
    /** {@code /api/webhooks/<id>/<token>} with an optional trailing slash, nothing else. */
    private static final Pattern PATH = Pattern.compile("/api/webhooks/(\\d{1,25})/([A-Za-z0-9_-]{1,100})/?");

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private TributeWebhook() {}

    /** {@code raw} trimmed, or {@code ""} for null. */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.strip();
    }

    /** Whether {@code raw} (after {@link #normalize}) is a Discord webhook link. */
    public static boolean isValid(String raw) {
        String url = normalize(raw);
        if (url.isEmpty() || url.length() > MAX_LENGTH) return false;
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return false;
        }
        if (!"https".equals(uri.getScheme())) return false;
        if (uri.getRawUserInfo() != null || uri.getPort() != -1) return false;
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) return false;
        String host = uri.getHost();
        if (host == null || !HOSTS.contains(host.toLowerCase(Locale.ROOT))) return false;
        String path = uri.getRawPath();
        return path != null && PATH.matcher(path).matches();
    }

    /**
     * The link with its token hidden, for showing on screen: {@code https://discord.com/api/webhooks/123/••••}.
     * {@code ""} for an invalid link.
     */
    public static String masked(String raw) {
        if (!isValid(raw)) return "";
        String url = normalize(raw);
        int cut = url.lastIndexOf('/', url.endsWith("/") ? url.length() - 2 : url.length() - 1);
        return url.substring(0, cut + 1) + "••••";
    }

    /** One embed: title, description, colour, and the attached image when there is one. */
    public static JsonObject embed(String title, String description, int color, boolean withImage) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", title);
        if (description != null && !description.isBlank()) embed.addProperty("description", description);
        embed.addProperty("color", color);
        if (withImage) {
            JsonObject image = new JsonObject();
            image.addProperty("url", "attachment://photo.png");
            embed.add("image", image);
        }
        return embed;
    }

    /**
     * The webhook message body: the player's name and head as the poster, the embed, and no pings of any
     * kind ({@code allowed_mentions.parse = []}) — names come from players and must never ping a channel.
     */
    static JsonObject payload(String username, UUID uuid, JsonObject embed) {
        JsonObject root = new JsonObject();
        String name = safeUsername(username);
        if (name != null) root.addProperty("username", name);
        if (uuid != null) root.addProperty("avatar_url", "https://mc-heads.net/avatar/" + uuid + "/64");
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        root.add("embeds", embeds);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        root.add("allowed_mentions", mentions);
        return root;
    }

    /**
     * A name Discord accepts as a webhook username, or null to keep the webhook's own. Discord refuses
     * names containing "discord" or "clyde" (the whole post fails), the same rule DiscordPresence keeps.
     */
    static String safeUsername(String name) {
        if (name == null || name.isBlank() || name.length() > 80) return null;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("discord") || lower.contains("clyde") ? null : name;
    }

    /**
     * Post to {@code url}. Async, best-effort, no retry. Completes with whether Discord accepted it; never
     * completes exceptionally, never throws, and never logs the link.
     *
     * @param png the photo, or null for a text-only post (Send test)
     */
    public static CompletableFuture<Boolean> post(String url, String username, UUID uuid, JsonObject embed, byte[] png) {
        if (!isValid(url) || embed == null) return CompletableFuture.completedFuture(false);
        try {
            HttpRequest request = request(normalize(url), payload(username, uuid, embed).toString(), png);
            return HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenApply(response -> {
                        boolean ok = response.statusCode() / 100 == 2;
                        if (!ok) {
                            LOGGER.info("[DungeonTrain] Tribute webhook post was refused: HTTP {}", response.statusCode());
                        }
                        return ok;
                    })
                    .exceptionally(t -> {
                        LOGGER.info("[DungeonTrain] Tribute webhook post failed: {}", t.getClass().getSimpleName());
                        return false;
                    });
        } catch (RuntimeException e) {
            LOGGER.info("[DungeonTrain] Tribute webhook post failed: {}", e.getClass().getSimpleName());
            return CompletableFuture.completedFuture(false);
        }
    }

    private static HttpRequest request(String url, String json, byte[] png) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", "DungeonTrain-Mod");
        if (png == null || png.length == 0) {
            return builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
        }
        String boundary = "DTBoundary" + UUID.randomUUID().toString().replace("-", "");
        return builder.header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, json, png)))
                .build();
    }

    /** {@code payload_json} then {@code files[0]} = photo.png. */
    static byte[] multipart(String boundary, String json, byte[] png) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + json.length() + 512);
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"payload_json\"\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + json + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"files[0]\"; filename=\"photo.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n";
        out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(png);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
