package games.brennan.dungeontrain.client.live;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.client.chat.emoji.DiscordEmojiCache;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import net.minecraft.network.chat.TextColor;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * The relay's chat source catalog ({@code GET <relay>/live/chat/sources}, dp-relay live-chat-sources.js): a label,
 * a name colour and an icon for each source the relay bridges. A source this build has no constant for
 * ({@link LiveFeedClient.Source#OTHER} with a {@link LiveFeedClient.ChatLine#sourceId}) is drawn from it, so a new
 * streaming platform's chat shows with its own icon and colour without a mod update.
 *
 * <p>Read when the Live chat connects and every {@link #REFRESH_MS} while it stays connected. Each icon goes through
 * {@link DiscordEmojiCache#requestUrl} (the same runtime glyph slots and consent as Discord's custom emoji) and only
 * ever from the relay the chat is read from: the catalog's icon path must be {@code /live/chat/sources/<id>.png}.
 * Until an icon has loaded the line shows the plain bubble.</p>
 */
public final class LiveChatSources {

    static final long REFRESH_MS = 30L * 60 * 1000;
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Pattern ICON_PATH = Pattern.compile("/live/chat/sources/[a-z0-9_]{1,24}\\.png(\\?v=[0-9a-f]{1,32})?");
    private static final int MAX_SOURCES = 64;

    /** One catalog entry: {@code iconKey} names its image in {@link DiscordEmojiCache} (null without an icon). */
    public record Entry(String label, @Nullable TextColor color, @Nullable String iconKey) {}

    private static volatile Map<String, Entry> catalog = Map.of();
    private static volatile long fetchedMs;
    private static volatile boolean inFlight;

    private LiveChatSources() {}

    /** The catalog entry for relay source {@code id}, or null. */
    @Nullable
    public static Entry entry(@Nullable String id) {
        return id == null ? null : catalog.get(id);
    }

    /** The font character drawing source {@code id}'s icon, once loaded ({@link DiscordEmojiCache#slot}). */
    public static OptionalInt glyph(@Nullable String id) {
        Entry e = entry(id);
        return e == null || e.iconKey() == null ? OptionalInt.empty() : DiscordEmojiCache.slot(e.iconKey());
    }

    /** Fetch the catalog from {@code base} if it was never read or is older than {@link #REFRESH_MS}. Client thread. */
    static void refresh(String base, long now) {
        if (inFlight || (fetchedMs != 0 && now - fetchedMs < REFRESH_MS)) return;
        inFlight = true;
        LiveFeedClient.chatSources(base).whenComplete((r, t) -> {
            inFlight = false;
            if (t != null || r == null || !r.ok()) return;
            fetchedMs = now;
            Map<String, Entry> next = parse(r.body());
            catalog = next;
            next.values().forEach(e -> {
                if (e.iconKey() != null) DiscordEmojiCache.requestUrl(e.iconKey(), URI.create(base + iconPathOf(e.iconKey())));
            });
        });
    }

    /** {@code {sources:{<id>:{label,color,icon}}}} → entries; anything malformed is left out. Pure. */
    static Map<String, Entry> parse(@Nullable JsonObject body) {
        Map<String, Entry> out = new HashMap<>();
        if (body == null || !body.has("sources") || !body.get("sources").isJsonObject()) return Map.of();
        for (Map.Entry<String, JsonElement> kv : body.getAsJsonObject("sources").entrySet()) {
            if (out.size() >= MAX_SOURCES) break;
            String id = LiveFeedClient.Source.id(kv.getKey());
            if (id == null || !kv.getValue().isJsonObject()) continue;
            JsonObject e = kv.getValue().getAsJsonObject();
            String label = string(e, "label");
            String color = string(e, "color");
            String icon = string(e, "icon");
            TextColor tc = color != null && COLOR.matcher(color).matches() ? TextColor.parseColor(color).result().orElse(null) : null;
            String iconKey = icon != null && ICON_PATH.matcher(icon).matches() ? KEY_PREFIX + icon : null;
            out.put(id, new Entry(label == null ? id : label.substring(0, Math.min(40, label.length())), tc, iconKey));
        }
        return Map.copyOf(out);
    }

    /** The image key for an icon path: unique per path and version, so a changed icon is fetched again. */
    private static final String KEY_PREFIX = "src:";

    private static String iconPathOf(String key) {
        return key.substring(KEY_PREFIX.length());
    }

    @Nullable
    private static String string(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    /** Tests only: a catalog as if read from the relay. */
    static void setForTest(Map<String, Entry> entries) {
        catalog = Map.copyOf(entries);
    }

    /** Tests only. */
    static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }
}
