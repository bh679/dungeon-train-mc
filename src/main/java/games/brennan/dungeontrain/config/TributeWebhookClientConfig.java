package games.brennan.dungeontrain.config;

import games.brennan.dungeontrain.discord.TributeWebhook;
import games.brennan.dungeontrain.discord.WebhookFeed;
import games.brennan.dungeontrain.discord.WebhookLink;
import games.brennan.dungeontrain.discord.WebhookSettings;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The player's own Discord webhooks ({@code config/dungeontrain-tribute-webhook-client.toml}, its name kept
 * from when it held only Tribute links): up to {@link TributeWebhook#MAX_LINKS} links, each with an optional
 * name and the feeds it gets ({@link WebhookFeed}). Names and feeds sit in their own lists keyed by the
 * webhook id ({@link WebhookSettings}), so the {@code urls} list reads exactly as it always has. A link with
 * no feeds entry was saved before feeds existed and gets Tributes only.
 *
 * <p>The links are secrets: they stay on this computer and go only to the server the player is on (memory
 * only there). Names never leave this computer.</p>
 */
public final class TributeWebhookClientConfig {

    public static final ModConfigSpec SPEC;
    /** The webhook links, possibly empty. */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> URLS;
    /** {@code "<webhook id>=<name>"}. */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> NAMES;
    /** {@code "<webhook id>=tributes,passenger_log"}. */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> FEEDS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("tributeWebhook");
        URLS = b
            .comment("Discord webhook links (https://discord.com/api/webhooks/...), up to " + TributeWebhook.MAX_LINKS + ".",
                     "Each gets what you pick for it in Options > Account > Discord webhooks.",
                     "Keep them private: anyone with a link can post to that channel.")
            .defineListAllowEmpty("urls", List.of(), () -> "", v -> v instanceof String s && TributeWebhook.isValid(s));
        NAMES = b
            .comment("Your name for each link, as <webhook id>=<name>. Only on this computer.")
            .defineListAllowEmpty("names", List.of(), () -> "", v -> v instanceof String);
        FEEDS = b
            .comment("What each link gets, as <webhook id>=tributes,passenger_log. A link not listed gets tributes.")
            .defineListAllowEmpty("feeds", List.of(), () -> "", v -> v instanceof String);
        b.pop();
        SPEC = b.build();
    }

    private TributeWebhookClientConfig() {}

    /** The saved links (valid, no repeats, capped), in the order added; empty when none or before load. */
    public static List<String> urls() {
        return SPEC.isLoaded() ? TributeWebhook.cleanList(URLS.get()) : List.of();
    }

    /** The saved links with their feeds, for the server. */
    public static List<WebhookLink> links() {
        Map<String, String> feeds = feedsMap();
        return urls().stream()
                .map(u -> new WebhookLink(u, WebhookSettings.feedsFor(feeds, TributeWebhook.webhookId(u))))
                .toList();
    }

    /** The saved links in the order the screen lists them: by name, unnamed last. */
    public static List<String> displayOrder() {
        Map<String, String> names = namesMap();
        return WebhookSettings.displayOrder(urls(), u -> nameIn(names, u));
    }

    public static boolean isSet() {
        return !urls().isEmpty();
    }

    /** Whether one more link can be saved. */
    public static boolean hasRoom() {
        return urls().size() < TributeWebhook.MAX_LINKS;
    }

    /** {@code url}'s name, or {@code ""} when it has none. */
    public static String name(String url) {
        return SPEC.isLoaded() ? nameIn(namesMap(), url) : "";
    }

    /** {@code url}'s feed mask. */
    public static int feeds(String url) {
        return SPEC.isLoaded() ? WebhookSettings.feedsFor(feedsMap(), TributeWebhook.webhookId(url)) : 0;
    }

    /**
     * Add {@code url} named {@code name} (blank for none), getting every feed. Returns false when it is
     * invalid, already saved, there is no room, or before load.
     */
    public static boolean add(String url, String name) {
        if (!SPEC.isLoaded() || !TributeWebhook.isValid(url)) return false;
        List<String> now = urls();
        if (now.size() >= TributeWebhook.MAX_LINKS || TributeWebhook.contains(now, url)) return false;
        List<String> next = new ArrayList<>(now);
        next.add(TributeWebhook.normalize(url));
        String id = TributeWebhook.webhookId(url);
        Map<String, String> names = namesMap();
        String clean = WebhookSettings.cleanName(name);
        if (clean.isEmpty()) names.remove(id); else names.put(id, clean);
        Map<String, String> feeds = feedsMap();
        feeds.put(id, WebhookFeed.encode(WebhookFeed.ALL));
        save(next, names, feeds);
        return true;
    }

    /** Rename {@code url}; a blank name clears it. */
    public static void rename(String url, String name) {
        if (!SPEC.isLoaded() || !TributeWebhook.contains(urls(), url)) return;
        Map<String, String> names = namesMap();
        String id = TributeWebhook.webhookId(url);
        String clean = WebhookSettings.cleanName(name);
        if (clean.isEmpty()) names.remove(id); else names.put(id, clean);
        save(urls(), names, feedsMap());
    }

    /** Switch {@code feed} on or off for {@code url}. */
    public static void toggle(String url, WebhookFeed feed) {
        if (!SPEC.isLoaded() || !TributeWebhook.contains(urls(), url)) return;
        Map<String, String> feeds = feedsMap();
        String id = TributeWebhook.webhookId(url);
        int mask = WebhookSettings.feedsFor(feeds, id) ^ feed.bit;
        feeds.put(id, WebhookFeed.encode(mask));
        save(urls(), namesMap(), feeds);
    }

    /** Remove {@code url} if saved, with its name and feeds. */
    public static void remove(String url) {
        if (!SPEC.isLoaded()) return;
        List<String> next = new ArrayList<>(urls());
        next.removeIf(u -> u.equals(url));
        save(next, namesMap(), feedsMap());
    }

    /** Remove every link. */
    public static void clear() {
        if (SPEC.isLoaded()) save(List.of(), Map.of(), Map.of());
    }

    private static String nameIn(Map<String, String> names, String url) {
        return WebhookSettings.cleanName(names.get(TributeWebhook.webhookId(url)));
    }

    private static Map<String, String> namesMap() {
        return WebhookSettings.parse(NAMES.get());
    }

    private static Map<String, String> feedsMap() {
        return WebhookSettings.parse(FEEDS.get());
    }

    /** Save the links, keeping names and feeds only for links still saved. */
    private static void save(List<String> links, Map<String, String> names, Map<String, String> feeds) {
        Set<String> ids = links.stream().map(TributeWebhook::webhookId).collect(Collectors.toSet());
        URLS.set(List.copyOf(links));
        NAMES.set(WebhookSettings.encode(names, ids));
        FEEDS.set(WebhookSettings.encode(feeds, ids));
        URLS.save(); // saves the whole file, names and feeds included
    }
}
