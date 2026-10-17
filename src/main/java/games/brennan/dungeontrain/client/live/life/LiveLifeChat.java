package games.brennan.dungeontrain.client.live.life;

import games.brennan.dungeontrain.discord.chat.ChatReaction;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatLine;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.LineReactions;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Source;
import games.brennan.dungeontrain.net.relay.LiveTakeoverClient.PollLine;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Counts the stream's chat for this life's "the crowd" page, from the page {@code LiveChatBridge}
 * already polls while the streamer is on air: lines other people said (and who said the most),
 * reactions on the streamer's own lines, and takeover votes on the streamer's Eye.
 */
public final class LiveLifeChat {

    private static final int MAX_MINE = 400;
    /** Seqs of the streamer's own lines: chat they sent and the moments they shared. */
    private static final Set<Long> MINE = new LinkedHashSet<>();

    private LiveLifeChat() {}

    /** A moment the streamer shared landed in the ring at {@code seq}: its reactions count. */
    public static void rememberMine(long seq) {
        if (seq <= 0) return;
        synchronized (MINE) {
            MINE.add(seq);
            if (MINE.size() > MAX_MINE) {
                Iterator<Long> it = MINE.iterator();
                it.next();
                it.remove();
            }
        }
    }

    private static boolean isMine(long seq) {
        synchronized (MINE) {
            return MINE.contains(seq);
        }
    }

    /** One polled page's lines, while streaming. {@code myId}: this player's uuid, 32-hex without dashes. */
    public static void onLines(List<ChatLine> lines, @Nullable String myId) {
        for (ChatLine line : lines) {
            if (line.poll() != null) {
                onPoll(line.poll(), myId);
            } else if (mine(line, myId)) {
                rememberMine(line.seq());
            } else if (counts(line)) {
                LiveLifeEvents.chat(line.ts() > 0 ? line.ts() : System.currentTimeMillis(), line.author(), line.content(),
                    authorOf(line));
            }
        }
    }

    /** Reaction updates: the latest total on each of the streamer's own lines. */
    public static void onReactions(List<LineReactions> updates) {
        for (LineReactions r : updates) {
            if (!isMine(r.line())) continue;
            int total = 0;
            for (ChatReaction c : r.list()) total += Math.max(0, c.count());
            LiveLifeEvents.reacted(r.line(), total);
        }
    }

    static void onPoll(PollLine p, @Nullable String myId) {
        if (p.open() || myId == null || p.holderUuid() == null) return;
        if (!p.holderUuid().replace("-", "").equalsIgnoreCase(myId)) return;
        if ("approved".equals(p.state()) || "rejected".equals(p.state())) {
            LiveLifeEvents.voteClosed(p.id(), "rejected".equals(p.state()));
        }
    }

    /** Where {@code line}'s author spoke from, for the podium ranking. */
    static LiveLifeStats.Author authorOf(ChatLine line) {
        String source = switch (line.source()) {
            case GAME -> "game";
            case WEB -> "web";
            case TWITCH -> "twitch";
            case DISCORD -> "discord";
            default -> "other";
        };
        String uuid = line.source() == Source.GAME && line.uuid() != null ? line.uuid().replace("-", "").toLowerCase(java.util.Locale.ROOT) : null;
        return new LiveLifeStats.Author(source, uuid);
    }

    static boolean mine(ChatLine line, @Nullable String myId) {
        return myId != null && line.uuid() != null && line.uuid().replace("-", "").equalsIgnoreCase(myId);
    }

    /** A line a person said: from the game, Discord, Twitch or the website, with text, not a moment. */
    static boolean counts(ChatLine line) {
        if (line.moment() != null || line.logRequest() != null) return false;
        if (line.source() == Source.OTHER) return false;
        return line.content() != null && !line.content().isBlank() && line.author() != null && !line.author().isBlank();
    }
}
