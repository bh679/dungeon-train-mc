package games.brennan.dungeontrain.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Renders Brennan's Discord presence as a chat line, read from the bundled Discord Presence mod's query
 * seam ({@code DiscordService.isDiscordUserOnline}/{@code lastSeenOnline}). Pure and absent-safe: the
 * presence is passed in as {@link Optional}s and {@code now} is injected, so the gate logic is unit-tested
 * without a running server, and an unknown presence yields {@code null} so callers omit the line.
 *
 * <p>Two renderings share one humaniser. {@link #recentLine} is the in-game {@code @dev}/{@code
 * @brennanhatton} chat reply — it speaks up only when Brennan is online now or was last seen within
 * {@link #MENTION_PRESENCE_WINDOW}, else stays silent (so a player tagging him learns whether he's around).
 * {@code EditorWelcome.buildPresenceLine} is the editor-onboarding sibling (any past time renders); both
 * format durations through {@link #humanizeAgo}.</p>
 */
public final class PresenceLine {

    private PresenceLine() {}

    /**
     * How recently Brennan must have been online for an in-game {@code @}-tag to draw a reply. Beyond this
     * (or when presence is unknown) {@link #recentLine}/{@link #recentPhrase} stay silent.
     */
    public static final Duration MENTION_PRESENCE_WINDOW = Duration.ofMinutes(30);

    /**
     * The text of the {@code @}-tag reply, or {@code null} to stay silent: "online right now" when online
     * now (winning over any stale last-seen); "was online {@literal <N>} ago." when last seen within
     * {@link #MENTION_PRESENCE_WINDOW}; {@code null} when offline longer than the window, never seen, or
     * presence is unknown. A future {@code lastSeen} (clock skew) is treated as out-of-window. Pure —
     * {@code now} is injected for tests.
     */
    static String recentPhrase(Optional<Boolean> online, Optional<Instant> lastSeen, Instant now) {
        if (online.orElse(false)) {
            return "Brennan is online on Discord right now!";
        }
        if (lastSeen.isPresent()) {
            Duration ago = Duration.between(lastSeen.get(), now);
            if (!ago.isNegative() && ago.compareTo(MENTION_PRESENCE_WINDOW) <= 0) {
                return "Brennan was online " + humanizeAgo(ago) + " ago.";
            }
        }
        return null; // > window, never seen, or unknown — say nothing
    }

    /**
     * The {@code @}-tag reply as a styled {@link Component} — green when online now, gray when recently
     * seen — or {@code null} to stay silent (when {@link #recentPhrase} is {@code null}).
     */
    public static Component recentLine(Optional<Boolean> online, Optional<Instant> lastSeen, Instant now) {
        String text = recentPhrase(online, lastSeen, now);
        if (text == null) {
            return null;
        }
        ChatFormatting color = online.orElse(false) ? ChatFormatting.GREEN : ChatFormatting.GRAY;
        return Component.literal(text).withStyle(color);
    }

    /**
     * Formats a positive elapsed {@link Duration} as a coarse human phrase — "5 minutes", "1 hour",
     * "3 days" — picking the largest whole unit (second → minute → hour → day) and pluralising. A zero or
     * negative duration (clock skew) clamps to "0 seconds".
     */
    public static String humanizeAgo(Duration d) {
        long secs = Math.max(0L, d.getSeconds());
        if (secs < 60L) return plural(secs, "second");
        long mins = secs / 60L;
        if (mins < 60L) return plural(mins, "minute");
        long hours = mins / 60L;
        if (hours < 24L) return plural(hours, "hour");
        return plural(hours / 24L, "day");
    }

    private static String plural(long n, String unit) {
        return n + " " + unit + (n == 1L ? "" : "s");
    }
}
