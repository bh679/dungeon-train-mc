package games.brennan.dungeontrain.discord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * One of a player's own Discord webhooks and the feeds it gets ({@link WebhookFeed} mask). What crosses the
 * wire and what the server keeps; the link's name never leaves the player's computer.
 */
public record WebhookLink(String url, int feeds) {

    public WebhookLink {
        url = TributeWebhook.normalize(url);
        feeds = WebhookFeed.clamp(feeds);
    }

    public boolean gets(WebhookFeed feed) {
        return feed.in(feeds);
    }

    /**
     * {@code raw} as the list that is kept: valid links only, no repeats (the first wins), at most
     * {@link TributeWebhook#MAX_LINKS}, in their original order.
     */
    public static List<WebhookLink> cleanList(Collection<WebhookLink> raw) {
        LinkedHashMap<String, WebhookLink> out = new LinkedHashMap<>();
        if (raw == null) return List.of();
        for (WebhookLink link : raw) {
            if (out.size() >= TributeWebhook.MAX_LINKS) break;
            if (link == null || !TributeWebhook.isValid(link.url())) continue;
            out.putIfAbsent(TributeWebhook.sameLinkKey(link.url()), link);
        }
        return List.copyOf(out.values());
    }

    /** The urls among {@code links} that get {@code feed}. */
    public static List<String> urlsFor(Collection<WebhookLink> links, WebhookFeed feed) {
        List<String> out = new ArrayList<>();
        if (links == null) return out;
        for (WebhookLink link : links) if (link.gets(feed)) out.add(link.url());
        return List.copyOf(out);
    }
}
