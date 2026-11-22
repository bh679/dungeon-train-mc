package games.brennan.dungeontrain.discord;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What one of a player's own Discord webhooks ({@link TributeWebhook}) receives. Each saved link has its
 * own set, stored as a bit mask: on the client next to the link, on the wire, and in the server mirror.
 */
public enum WebhookFeed {
    /** Every photo the player pays Tribute to. */
    TRIBUTES(1, "tributes"),
    /** The player's own passenger log posts: death manifest, milestones, photos of them, echo stories. */
    PASSENGER_LOG(2, "passenger_log");

    /** Every feed: what a newly added link gets. */
    public static final int ALL = TRIBUTES.bit | PASSENGER_LOG.bit;
    /** What a link saved before feeds existed gets: it was a Tribute webhook. */
    public static final int LEGACY = TRIBUTES.bit;

    public final int bit;
    /** Its name in the client config file. */
    public final String key;

    WebhookFeed(int bit, String key) {
        this.bit = bit;
        this.key = key;
    }

    public boolean in(int mask) {
        return (mask & bit) != 0;
    }

    /** {@code mask} with only known feed bits. */
    public static int clamp(int mask) {
        return mask & ALL;
    }

    /** {@code "tributes,passenger_log"}; {@code ""} for none. */
    public static String encode(int mask) {
        List<String> keys = new ArrayList<>();
        for (WebhookFeed f : values()) if (f.in(mask)) keys.add(f.key);
        return String.join(",", keys);
    }

    /** The mask named by {@link #encode}'s output. Unknown names are ignored. */
    public static int decode(String keys) {
        int mask = 0;
        if (keys == null) return 0;
        for (String k : keys.split(",")) {
            String key = k.strip().toLowerCase(Locale.ROOT);
            for (WebhookFeed f : values()) if (f.key.equals(key)) mask |= f.bit;
        }
        return mask;
    }
}
