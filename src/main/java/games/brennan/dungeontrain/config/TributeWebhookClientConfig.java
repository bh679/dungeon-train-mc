package games.brennan.dungeontrain.config;

import games.brennan.dungeontrain.discord.TributeWebhook;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * The player's Tribute webhooks ({@code config/dungeontrain-tribute-webhook-client.toml}): Discord webhook
 * links that every photo they pay Tribute to is also posted to, up to {@link TributeWebhook#MAX_LINKS}.
 * Its own small file, like the other client configs, so the display config record stays untouched. The
 * links are secrets: they stay on this computer and go only to the server the player is on (memory only
 * there).
 */
public final class TributeWebhookClientConfig {

    public static final ModConfigSpec SPEC;
    /** The webhook links, possibly empty. */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> URLS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("tributeWebhook");
        URLS = b
            .comment("Discord webhook links (https://discord.com/api/webhooks/...). Every photo you pay Tribute to",
                     "is also posted to each of them, up to " + TributeWebhook.MAX_LINKS + ". Set them in Options > Account.",
                     "Keep them private: anyone with a link can post to that channel.")
            .defineListAllowEmpty("urls", List.of(), () -> "", v -> v instanceof String s && TributeWebhook.isValid(s));
        b.pop();
        SPEC = b.build();
    }

    private TributeWebhookClientConfig() {}

    /** The saved links (valid, no repeats, capped); empty when none or before load. */
    public static List<String> urls() {
        return SPEC.isLoaded() ? TributeWebhook.cleanList(URLS.get()) : List.of();
    }

    public static boolean isSet() {
        return !urls().isEmpty();
    }

    /** Whether one more link can be saved. */
    public static boolean hasRoom() {
        return urls().size() < TributeWebhook.MAX_LINKS;
    }

    /** Add {@code url}. Returns false when it is invalid, already saved, there is no room, or before load. */
    public static boolean add(String url) {
        if (!SPEC.isLoaded() || !TributeWebhook.isValid(url)) return false;
        List<String> now = urls();
        if (now.size() >= TributeWebhook.MAX_LINKS || TributeWebhook.contains(now, url)) return false;
        List<String> next = new ArrayList<>(now);
        next.add(TributeWebhook.normalize(url));
        save(next);
        return true;
    }

    /** Remove {@code url} if saved. */
    public static void remove(String url) {
        if (!SPEC.isLoaded()) return;
        List<String> next = new ArrayList<>(urls());
        next.removeIf(u -> u.equals(url));
        save(next);
    }

    /** Remove every link. */
    public static void clear() {
        if (SPEC.isLoaded()) save(List.of());
    }

    private static void save(List<String> links) {
        URLS.set(List.copyOf(links));
        URLS.save();
    }
}
