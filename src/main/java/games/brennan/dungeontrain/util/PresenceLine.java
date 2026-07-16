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
     * The presence decision shared by {@link #recentPhrase} (English test seam) and {@link #recentLine}
     * (localized component): {@code onlineNow} means Brennan is online right now (winning over any stale
     * last-seen); otherwise {@code ago} is the in-window elapsed time since he was last seen. {@code null}
     * from {@link #decide} means "stay silent". Keeping the gate in one place stops the two renderings from
     * drifting apart.
     */
    private record Presence(boolean onlineNow, Duration ago) {}

    /**
     * The online/window gate: online now → {@code onlineNow}; last seen within
     * {@link #MENTION_PRESENCE_WINDOW} → {@code ago}; else {@code null} (offline longer than the window,
     * never seen, unknown presence, or a future {@code lastSeen} from clock skew). Pure — {@code now} is
     * injected for tests.
     */
    private static Presence decide(Optional<Boolean> online, Optional<Instant> lastSeen, Instant now) {
        if (online.orElse(false)) {
            return new Presence(true, null);
        }
        if (lastSeen.isPresent()) {
            Duration ago = Duration.between(lastSeen.get(), now);
            if (!ago.isNegative() && ago.compareTo(MENTION_PRESENCE_WINDOW) <= 0) {
                return new Presence(false, ago);
            }
        }
        return null; // > window, never seen, or unknown — say nothing
    }

    /**
     * The English text of the {@code @}-tag reply, or {@code null} to stay silent. Retained as the pure
     * unit-tested seam ({@code PresenceLineTest}); the player-facing line is the localized
     * {@link #recentLine}, which mirrors these exact phrasings via {@code en_us.json}.
     */
    static String recentPhrase(Optional<Boolean> online, Optional<Instant> lastSeen, Instant now) {
        Presence p = decide(online, lastSeen, now);
        if (p == null) {
            return null;
        }
        return p.onlineNow()
            ? "Brennan is online on Discord right now!"
            : "Brennan was online " + humanizeAgo(p.ago()) + " ago.";
    }

    /**
     * The {@code @}-tag reply as a localized, styled {@link Component} — green when online now, gray when
     * recently seen — or {@code null} to stay silent. Uses {@code chat.dungeontrain.presence.*} keys so the
     * line renders in each client's own language; the duration is a nested {@link #agoComponent}.
     */
    public static Component recentLine(Optional<Boolean> online, Optional<Instant> lastSeen, Instant now) {
        Presence p = decide(online, lastSeen, now);
        if (p == null) {
            return null;
        }
        if (p.onlineNow()) {
            return Component.translatable("chat.dungeontrain.presence.online_now").withStyle(ChatFormatting.GREEN);
        }
        return Component.translatable("chat.dungeontrain.presence.was_online", agoComponent(p.ago()))
            .withStyle(ChatFormatting.GRAY);
    }

    /** The largest whole time unit and its count for an elapsed duration; the shared basis of both renderings. */
    private enum Unit { second, minute, hour, day }

    /** A count paired with the largest whole {@link Unit} it fits — e.g. 7 + {@code minute}. */
    private record Elapsed(long count, Unit unit) {}

    /**
     * Picks the largest whole unit (second → minute → hour → day) for a positive elapsed {@link Duration}.
     * A zero or negative duration (clock skew) clamps to 0 seconds. Single source of the unit thresholds so
     * {@link #humanizeAgo} (English) and {@link #agoComponent} (localized) never drift apart.
     */
    private static Elapsed largestUnit(Duration d) {
        long secs = Math.max(0L, d.getSeconds());
        if (secs < 60L) return new Elapsed(secs, Unit.second);
        long mins = secs / 60L;
        if (mins < 60L) return new Elapsed(mins, Unit.minute);
        long hours = mins / 60L;
        if (hours < 24L) return new Elapsed(hours, Unit.hour);
        return new Elapsed(hours / 24L, Unit.day);
    }

    /**
     * Formats a positive elapsed {@link Duration} as a coarse English phrase — "5 minutes", "1 hour",
     * "3 days" — picking the largest whole unit and pluralising. A zero or negative duration clamps to
     * "0 seconds". Kept as the pure English seam exercised by {@code EditorWelcomeTest}.
     */
    public static String humanizeAgo(Duration d) {
        Elapsed e = largestUnit(d);
        return e.count() + " " + e.unit().name() + (e.count() == 1L ? "" : "s");
    }

    /**
     * The elapsed {@link Duration} as a localized "N unit" {@link Component} — "7 minutes" / "7 分钟" — via
     * the {@code chat.dungeontrain.time.*} keys, picking the singular or plural key by count. Passed as the
     * {@code %s} argument of the presence lines so the whole sentence renders in the client's language.
     */
    public static Component agoComponent(Duration d) {
        Elapsed e = largestUnit(d);
        String key = e.unit().name() + (e.count() == 1L ? "" : "s");
        return Component.translatable("chat.dungeontrain.time." + key, e.count());
    }
}
