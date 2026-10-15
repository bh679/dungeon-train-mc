package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.client.ClientLanguage;
import games.brennan.dungeontrain.narrative.PluralRules;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The words of the Live Eye takeover prompt: "X has been streaming for 4 minutes, this is their 3rd
 * time streaming". Durations are shown in their largest whole unit; ordinals pick a key by English's
 * st/nd/rd/th, which other languages fill with one form of their own ("3." / "第3次").
 */
public final class LiveTakeoverText {

    private static final String KEY = "screen.dungeontrain.live_takeover.";

    private LiveTakeoverText() {}

    /** The unit a duration is shown in: seconds under a minute, minutes under an hour, else hours. */
    enum Unit {
        SECONDS(1_000L), MINUTES(60_000L), HOURS(3_600_000L);

        final long ms;

        Unit(long ms) { this.ms = ms; }
    }

    /** The largest unit {@code ms} fills at least once (seconds for anything shorter than a minute). */
    static Unit unitOf(long ms) {
        if (ms >= Unit.HOURS.ms) return Unit.HOURS;
        if (ms >= Unit.MINUTES.ms) return Unit.MINUTES;
        return Unit.SECONDS;
    }

    /** {@code ms} as a whole count of {@link #unitOf} — floored, so "4 minutes" until the fifth is complete. */
    static long countOf(long ms) {
        return Math.max(0, ms) / unitOf(ms).ms;
    }

    /** English's ordinal suffix for {@code n}: 1st, 2nd, 3rd, 4th, 11th, 12th, 13th, 21st, 112th. */
    static String englishSuffix(long n) {
        long lastTwo = Math.abs(n) % 100;
        if (lastTwo >= 11 && lastTwo <= 13) return "th";
        return switch ((int) (Math.abs(n) % 10)) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    /** "4 minutes", in the player's language and its plural forms. */
    public static MutableComponent duration(long ms) {
        Unit unit = unitOf(ms);
        return PluralRules.clause(ClientLanguage.selected(), KEY + unit.name().toLowerCase(java.util.Locale.ROOT), countOf(ms));
    }

    /**
     * "3rd": one key per English ending ({@code ordinal_st} … {@code ordinal_th}), chosen by English's
     * rule; a language without those endings writes its own form in all four.
     */
    public static MutableComponent ordinal(int n) {
        return Component.translatable(KEY + "ordinal_" + englishSuffix(n), n);
    }

    /** "Alice has been streaming for 4 minutes, this is their 3rd time streaming." */
    public static MutableComponent holderLine(String holder, long tenureMs, int streamCount) {
        return Component.translatable(KEY + "holder", holder, duration(tenureMs), ordinal(streamCount));
    }
}
