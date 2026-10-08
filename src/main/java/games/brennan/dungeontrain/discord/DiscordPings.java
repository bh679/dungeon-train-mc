package games.brennan.dungeontrain.discord;

import games.brennan.discordpresence.config.DiscordPresenceConfig;
import games.brennan.dungeontrain.DungeonTrain;

import java.util.UUID;

/**
 * Asks the relay to @-ping a player on a post that is theirs — a photo of theirs, their death report —
 * when they have linked their Discord account ({@code /discord}, {@code client.credits.DiscordLinkCommand}).
 *
 * <p>The mod never knows a Discord id. It says who a post is about by putting a hint on the {@code /hook}
 * URL it hands Discord Presence as the {@code webhookOverride}: {@code ping=<uuid>}, or for a found photo
 * {@code ping_photo=<id>&photo_cap=<cap>} (the relay knows who uploaded it; the frame only carries a
 * name). The relay (dp-relay {@code hookpings.js}) strips the hint, and pings only a linked member with
 * pings on. An unlinked player's post goes out exactly as before.</p>
 *
 * <p>Only through the relay: a direct-webhook install (DP not in relay mode, or a dev webhook
 * override) gets the URL back untouched — Discord itself would just ignore the hint, but there is no
 * reason to send it.</p>
 */
public final class DiscordPings {

    private DiscordPings() {}

    /**
     * The webhook override for a {@code type} post about {@code player}: {@code override} (or DP's default
     * hook) + {@code ping=} + {@code ping_type=} — the player may have turned that kind off.
     */
    public static String forPlayer(String override, UUID player, PingType type) {
        String base = base(override);
        return base == null || player == null ? override : withParam(base, playerHint(player, type));
    }

    /** The webhook override for a post of found photo {@code photoId}, pinging whoever took it. */
    public static String forFoundPhoto(String override, int photoId) {
        String base = base(override);
        String cap = capSegment(DungeonTrain.relayBaseUrl());
        if (base == null || photoId <= 0 || cap.isEmpty()) return override;
        return withParam(base, photoHint(photoId, cap));
    }

    static String playerHint(UUID player, PingType type) {
        return "ping=" + undashed(player) + "&ping_type=" + type.wireId();
    }

    static String photoHint(int photoId, String cap) {
        return "ping_photo=" + photoId + "&photo_cap=" + cap + "&ping_type=" + PingType.PHOTO_TRIBUTED.wireId();
    }

    /** {@code override} when set, else DP's own relay hook; {@code null} when posts don't go through the relay. */
    private static String base(String override) {
        try {
            if (!DiscordPresenceConfig.isRelayMode() || DiscordPresenceConfig.isDevWebhookOverrideActive()) return null;
            String url = override != null && !override.isBlank() ? override : DiscordPresenceConfig.getWebhookUrl();
            return url == null || url.isBlank() ? null : url;
        } catch (Throwable t) {
            return null; // config not loaded yet — post without a ping
        }
    }

    /** {@code url} with {@code param} appended, {@code ?} or {@code &} as the URL needs. */
    static String withParam(String url, String param) {
        return url + (url.contains("?") ? "&" : "?") + param;
    }

    /** The cap segment of a relay base URL: its last path segment ({@code https://host/<cap>} → {@code <cap>}). */
    static String capSegment(String baseUrl) {
        if (baseUrl == null) return "";
        String trimmed = baseUrl.replaceAll("/+$", "");
        int slash = trimmed.lastIndexOf('/');
        String seg = slash < 0 ? "" : trimmed.substring(slash + 1);
        return seg.matches("[A-Za-z0-9_-]{1,128}") ? seg : "";
    }

    static String undashed(UUID uuid) {
        return uuid.toString().replace("-", "");
    }
}
