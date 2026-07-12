package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.net.relay.BookStatsClient;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The "a familiar book…" flavour line shown to an author who is holding a community-pool LOOT copy of a
 * book they wrote (see {@link FamiliarBookGreeter}). Always reports how many people have held (opened)
 * the book; the second half rotates through one "reception" stat — how it's doing out in the world.
 *
 * <p>Delivered via {@link net.minecraft.server.level.ServerPlayer#sendSystemMessage}, styled
 * {@link ChatFormatting#GRAY} to match {@link SharedBookMessage} (the burn line) — the quiet, "your
 * words are out there somewhere" voice the shared-book feature speaks in.</p>
 *
 * <p>Ten variants. A variant is only eligible when the stat it names is meaningful (&gt; 0), so a book
 * no one has finished never shows "0 read it to completion"; variant #10 (held-count only) is always
 * eligible, so there is always a line to show.</p>
 */
public final class FamiliarBookMessage {

    private FamiliarBookMessage() {}

    private record Variant(Predicate<BookStatsClient.Stats> eligible,
                           Function<BookStatsClient.Stats, String> render) {}

    private static final List<Variant> VARIANTS = List.of(
        // read to completion (the base example)
        new Variant(s -> s.completers() > 0,
            s -> "A familiar book. It feels like " + people(s.held()) + " " + have(s.held())
                + " held it, and " + s.completers() + " read it to the very end."),
        new Variant(s -> s.completers() > 0,
            s -> "This book knows your hand — some " + people(s.held()) + " " + have(s.held())
                + " held it; " + s.completers() + " saw it through to the last page."),
        // longest single read
        new Variant(s -> s.longestReadMs() > 0,
            s -> "A familiar weight. Around " + people(s.held()) + " " + have(s.held())
                + " held it, and one reader stayed with it for " + dur(s.longestReadMs()) + " straight."),
        new Variant(s -> s.longestReadMs() > 0,
            s -> "You've felt this before. " + people(s.held()) + " " + have(s.held())
                + " held it; the longest reading ran " + dur(s.longestReadMs()) + "."),
        // longest time on one page (+ which page)
        new Variant(s -> s.longestPageMs() > 0,
            s -> "A familiar book. " + people(s.held()) + " " + have(s.held())
                + " held it, and someone lingered longest on page " + (s.longestPageIndex() + 1)
                + " — " + dur(s.longestPageMs()) + " there alone."),
        new Variant(s -> s.longestPageMs() > 0,
            s -> "Your words come round again. " + people(s.held()) + " " + have(s.held())
                + " held it; page " + (s.longestPageIndex() + 1) + " held one reader for " + dur(s.longestPageMs()) + "."),
        // total opens
        new Variant(s -> s.opens() > 0,
            s -> "A familiar book. It feels like " + people(s.held()) + " " + have(s.held())
                + " held it, opened " + times(s.opens()) + " in all."),
        // page turns
        new Variant(s -> s.pageTurns() > 0,
            s -> "This book remembers you. " + people(s.held()) + " " + have(s.held())
                + " held it, its pages turned " + times(s.pageTurns()) + " over."),
        // re-reads
        new Variant(s -> s.rereads() > 0,
            s -> "A familiar book. " + people(s.held()) + " " + have(s.held())
                + " held it — and " + times(s.rereads()) + " someone couldn't help but read it again."),
        // held-count only — always eligible, the fallback
        new Variant(s -> true,
            s -> "A familiar book. It feels like " + people(s.held()) + " " + have(s.held())
                + " held your words.")
    );

    /**
     * Build the gray familiar-book line for {@code stats}, choosing a random variant among those whose
     * stat is meaningful. Never returns null — the held-count variant is always eligible.
     */
    public static Component build(BookStatsClient.Stats stats, RandomSource rng) {
        List<Variant> eligible = new ArrayList<>();
        for (Variant v : VARIANTS) if (v.eligible().test(stats)) eligible.add(v);
        Variant chosen = eligible.get(rng.nextInt(eligible.size()));
        return Component.literal(chosen.render().apply(stats)).withStyle(ChatFormatting.GRAY);
    }

    // ---- small grammar / formatting helpers ------------------------------------

    /** "1 person" / "N people". */
    private static String people(int n) {
        return n + (n == 1 ? " person" : " people");
    }

    /** Subject-verb agreement for the held count: "has" / "have". */
    private static String have(int n) {
        return n == 1 ? "has" : "have";
    }

    /** "1 time" / "N times". */
    private static String times(int n) {
        return n + (n == 1 ? " time" : " times");
    }

    /** Human duration: "45s", "3m 12s", "1h 4m". Sub-second rounds down to "0s". */
    private static String dur(long ms) {
        long totalSec = Math.max(0, ms / 1000);
        long h = totalSec / 3600, m = (totalSec % 3600) / 60, s = totalSec % 60;
        if (h > 0) return m > 0 ? (h + "h " + m + "m") : (h + "h");
        if (m > 0) return s > 0 ? (m + "m " + s + "s") : (m + "m");
        return s + "s";
    }
}
