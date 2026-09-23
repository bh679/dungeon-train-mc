package games.brennan.dungeontrain.discord;

import games.brennan.discordpresence.config.DiscordPresenceConfig;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * The content line that tags Discord-linked players on a public passenger log or echo log.
 *
 * <p>DT never learns Discord ids (see {@code CommunityLinkClient}): a player links with
 * {@code /discord link} (or Credits) → {@code /dtlink <code>} in Discord, and the relay keeps the
 * uuid → Discord id map. So a post carries one {@code <dt-ping:UUID>} marker per player it is about;
 * the relay ({@code hook-mentions.js}) turns each linked one into a real ping and removes the rest,
 * so an unlinked player sees nothing.</p>
 *
 * <p>Only emitted in relay mode — a raw webhook (e.g. the dev-webhook override) would print the
 * marker text verbatim.</p>
 */
public final class LogPings {

    /** The relay resolves at most this many markers per post; more would just be dropped there. */
    static final int MAX_PER_POST = 3;

    private LogPings() {}

    /** Markers for {@code players} (nulls skipped, deduped, order kept), or {@code null} when not in relay mode. */
    public static String content(UUID... players) {
        if (!relayMode()) return null;
        return markers(Arrays.asList(players));
    }

    /** Pure: {@code <dt-ping:…>} markers joined by spaces, {@code null} when there are none. */
    static String markers(Collection<UUID> players) {
        Set<UUID> unique = new LinkedHashSet<>();
        for (UUID id : players) {
            if (id != null) unique.add(id);
        }
        if (unique.isEmpty()) return null;
        StringJoiner out = new StringJoiner(" ");
        unique.stream().limit(MAX_PER_POST).map(Objects::toString)
                .forEach(id -> out.add("<dt-ping:" + id + ">"));
        return out.toString();
    }

    private static boolean relayMode() {
        try {
            return DiscordPresenceConfig.isRelayMode();
        } catch (Throwable t) {
            return false; // config not loaded (early / test) → never risk a raw marker
        }
    }
}
