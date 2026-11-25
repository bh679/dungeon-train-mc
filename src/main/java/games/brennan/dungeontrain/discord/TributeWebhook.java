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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * A player's own Discord or Fluxer webhook, set on the Options Account tab. Fluxer's execute route takes
 * Discord's body (username, avatar_url, embeds, allowed_mentions, payload_json + files[0]), so one post fits both. Each link gets the feeds the player
 * picked for it ({@link WebhookFeed}): the photos they pay Tribute to ({@link TributePhotoReporter#postPersonal})
 * and their own passenger log posts ({@link PassengerLogWebhooks}).
 *
 * <p>The link is a secret (anyone holding it can post to that channel), and on a multiplayer server it
 * is a URL the <i>server</i> fetches on a player's say-so. So: {@link #isValid} accepts a Discord or Fluxer
 * webhook link on an official host and nothing else, the server re-checks it on receipt, and nothing here ever logs it. Posts are
 * best-effort and never retried or queued to disk.</p>
 *
 * <p>No Minecraft types: shared by the client (Send test) and the server (the Tribute post).</p>
 */
public final class TributeWebhook {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Longest link accepted, in the Options field and on the wire. Real ones are about 120. */
    public static final int MAX_LENGTH = 256;
    /**
     * Most links a player can save. Each Tribute uploads the photo once per link, from the server on a
     * multiplayer world, so this caps what one player can make a shared server do per Tribute.
     */
    public static final int MAX_LINKS = 25;

    /** {@code /webhooks/<id>/<token>} with an optional trailing slash, behind each platform's prefix. */
    private static final String WEBHOOK = "/webhooks/(\\d{1,25})/([A-Za-z0-9_-]{1,100})/?";
    /** Discord: {@code /api/webhooks/<id>/<token>}, nothing else. */
    private static final Pattern DISCORD_PATH = Pattern.compile("/api" + WEBHOOK);
    /** Fluxer's public API under {@code /api}, with or without its {@code /v1} version. */
    private static final Pattern FLUXER_API_PATH = Pattern.compile("/api(?:/v1)?" + WEBHOOK);
    /** Fluxer's API host, which serves the same routes at its root. */
    private static final Pattern FLUXER_ROOT_PATH = Pattern.compile("(?:/v1)?" + WEBHOOK);

    /**
     * Every host a link may point at, with the path it must have there. Official hosts only: on a
     * multiplayer server the server fetches the link, so a self-hosted Fluxer (any host) is refused.
     */
    private static final Map<String, Pattern> PATHS = Map.of(
            "discord.com", DISCORD_PATH,
            "discordapp.com", DISCORD_PATH,
            "ptb.discord.com", DISCORD_PATH,
            "canary.discord.com", DISCORD_PATH,
            "fluxer.com", FLUXER_API_PATH,
            "web.fluxer.app", FLUXER_API_PATH,
            "api.fluxer.app", FLUXER_ROOT_PATH);

    /** Discord's embed limits: a post over any of them is refused whole. */
    static final int MAX_TITLE = 256;
    static final int MAX_DESCRIPTION = 4096;
    static final int MAX_FIELD_NAME = 256;
    static final int MAX_FIELD_VALUE = 1024;
    static final int MAX_FIELDS = 25;
    /** The whole embed's text, title + description + fields, is capped at 6000; this leaves room. */
    static final int MAX_EMBED_TEXT = 5800;

    /** The default attachment name, kept for the Tribute posts. */
    public static final String PHOTO_FILENAME = "photo.png";

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

    /** Whether {@code raw} (after {@link #normalize}) is a Discord or Fluxer webhook link. */
    public static boolean isValid(String raw) {
        return match(raw) != null;
    }

    /** The matched webhook path of a valid link (group 1 = id, group 2 = token), or null. */
    private static java.util.regex.Matcher match(String raw) {
        String url = normalize(raw);
        if (url.isEmpty() || url.length() > MAX_LENGTH) return null;
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return null;
        }
        if (!"https".equals(uri.getScheme())) return null;
        if (uri.getRawUserInfo() != null || uri.getPort() != -1) return null;
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
        String host = uri.getHost();
        String path = uri.getRawPath();
        if (host == null || path == null) return null;
        Pattern pattern = PATHS.get(host.toLowerCase(Locale.ROOT));
        if (pattern == null) return null;
        java.util.regex.Matcher m = pattern.matcher(path);
        return m.matches() ? m : null;
    }

    /** {@code "Discord"} or {@code "Fluxer"} for a valid link, {@code ""} otherwise. */
    public static String platform(String raw) {
        if (!isValid(raw)) return "";
        String host = URI.create(normalize(raw)).getHost().toLowerCase(Locale.ROOT);
        return host.endsWith("fluxer.com") || host.endsWith("fluxer.app") ? "Fluxer" : "Discord";
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

    /**
     * {@code raw} as the list that is kept: valid links only, trimmed, no repeats (a trailing slash is
     * the same link), at most {@link #MAX_LINKS}, in their original order.
     */
    public static java.util.List<String> cleanList(java.util.Collection<? extends String> raw) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        if (raw == null) return java.util.List.of();
        for (String s : raw) {
            if (out.size() >= MAX_LINKS) break;
            if (!isValid(s)) continue;
            String url = normalize(s);
            out.putIfAbsent(sameLinkKey(url), url);
        }
        return java.util.List.copyOf(out.values());
    }

    /** Two spellings of one link compare equal: host case and a trailing slash don't matter. */
    static String sameLinkKey(String url) {
        String u = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        try {
            URI uri = new URI(u);
            if (uri.getHost() != null && uri.getRawPath() != null) {
                return uri.getScheme() + "://" + uri.getHost().toLowerCase(Locale.ROOT) + uri.getRawPath();
            }
        } catch (URISyntaxException ignored) {
            // not a link: compare as typed
        }
        return u;
    }

    /** The webhook's numeric id (not secret), or {@code ""} for an invalid link. */
    public static String webhookId(String raw) {
        java.util.regex.Matcher m = match(raw);
        return m == null ? "" : m.group(1);
    }

    /** Whether {@code url} is already in {@code links} (as {@link #sameLinkKey} sees it). */
    public static boolean contains(java.util.List<String> links, String url) {
        String key = sameLinkKey(normalize(url));
        return links.stream().anyMatch(l -> sameLinkKey(l).equals(key));
    }

    /** One labelled embed field. */
    public record Field(String name, String value) {}

    /** One embed: title, description, colour, and the attached {@code photo.png} when there is one. */
    public static JsonObject embed(String title, String description, int color, boolean withImage) {
        return embed(title, description, color, List.of(), withImage ? PHOTO_FILENAME : null);
    }

    /**
     * One embed with fields, cut to Discord's limits so a long story never gets the whole post refused.
     *
     * @param imageFilename the attachment shown as the embed image, or null for none
     */
    public static JsonObject embed(String title, String description, int color, List<Field> fields, String imageFilename) {
        JsonObject embed = new JsonObject();
        String t = cut(title == null || title.isBlank() ? "Dungeon Train" : title, MAX_TITLE);
        int budget = MAX_EMBED_TEXT - t.length();
        embed.addProperty("title", t);
        if (description != null && !description.isBlank()) {
            String d = cut(description, Math.min(MAX_DESCRIPTION, budget));
            budget -= d.length();
            embed.addProperty("description", d);
        }
        embed.addProperty("color", color);
        JsonArray out = new JsonArray();
        for (Field f : fields == null ? List.<Field>of() : fields) {
            if (out.size() >= MAX_FIELDS || f == null || isBlank(f.name()) || isBlank(f.value())) continue;
            String name = cut(f.name(), MAX_FIELD_NAME);
            String value = cut(f.value(), MAX_FIELD_VALUE);
            if (name.length() + value.length() > budget) break;
            budget -= name.length() + value.length();
            JsonObject field = new JsonObject();
            field.addProperty("name", name);
            field.addProperty("value", value);
            out.add(field);
        }
        if (!out.isEmpty()) embed.add("fields", out);
        if (imageFilename != null) {
            JsonObject image = new JsonObject();
            image.addProperty("url", "attachment://" + imageFilename);
            embed.add("image", image);
        }
        return embed;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** {@code s} at most {@code max} characters, ending in an ellipsis when cut. */
    static String cut(String s, int max) {
        if (s.length() <= max) return s;
        return max <= 1 ? s.substring(0, Math.max(0, max)) : s.substring(0, max - 1) + "…";
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
        return post(url, username, uuid, embed, png, PHOTO_FILENAME);
    }

    /**
     * {@link #post(String, String, UUID, JsonObject, byte[])} with the attachment named {@code filename}
     * ({@code .jpg} is sent as a JPEG, anything else as a PNG). The embed must name the same file.
     */
    public static CompletableFuture<Boolean> post(String url, String username, UUID uuid, JsonObject embed,
                                                  byte[] image, String filename) {
        if (!isValid(url) || embed == null) return CompletableFuture.completedFuture(false);
        try {
            HttpRequest request = request(normalize(url), payload(username, uuid, embed).toString(), image,
                    filename == null ? PHOTO_FILENAME : filename);
            return HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenApply(response -> {
                        boolean ok = response.statusCode() / 100 == 2;
                        if (!ok) {
                            LOGGER.info("[DungeonTrain] Personal webhook post was refused: HTTP {}", response.statusCode());
                        }
                        return ok;
                    })
                    .exceptionally(t -> {
                        LOGGER.info("[DungeonTrain] Personal webhook post failed: {}", t.getClass().getSimpleName());
                        return false;
                    });
        } catch (RuntimeException e) {
            LOGGER.info("[DungeonTrain] Personal webhook post failed: {}", e.getClass().getSimpleName());
            return CompletableFuture.completedFuture(false);
        }
    }

    private static HttpRequest request(String url, String json, byte[] png, String filename) {
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
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, json, png, filename)))
                .build();
    }

    /** {@code payload_json} then {@code files[0]} = photo.png. */
    static byte[] multipart(String boundary, String json, byte[] png) {
        return multipart(boundary, json, png, PHOTO_FILENAME);
    }

    /** {@code payload_json} then {@code files[0]} = {@code filename}. */
    static byte[] multipart(String boundary, String json, byte[] png, String filename) {
        String type = filename.toLowerCase(Locale.ROOT).endsWith(".jpg") ? "image/jpeg" : "image/png";
        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + json.length() + 512);
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"payload_json\"\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + json + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"files[0]\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + type + "\r\n\r\n";
        out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(png);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
