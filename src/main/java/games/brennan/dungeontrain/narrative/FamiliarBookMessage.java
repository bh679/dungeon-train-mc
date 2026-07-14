package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.net.relay.BookStatsClient;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
 *
 * <p><b>Localization.</b> Each variant, and the count-driven sub-phrases it embeds, is a
 * {@link Component#translatable} key under {@code chat.dungeontrain.familiar_book.*}. The line is built
 * on the server (it picks the random variant and the singular/plural form by count) but rendered on the
 * client in that client's language — so a Chinese client sees Chinese. English pluralization
 * (person/people, has/have, time/times) is collapsed into single count-selected sub-keys
 * ({@code held.one}/{@code held.other}, {@code times.one}/{@code times.other}), which languages without
 * that distinction simply map to one string.</p>
 */
public final class FamiliarBookMessage {

    private FamiliarBookMessage() {}

    /** Common prefix for every familiar-book lang key. */
    private static final String KEY = "chat.dungeontrain.familiar_book.";

    private record Variant(Predicate<BookStatsClient.Stats> eligible,
                           Function<BookStatsClient.Stats, MutableComponent> render) {}

    private static final List<Variant> VARIANTS = List.of(
        // read to completion (the base example)
        new Variant(s -> s.completers() > 0,
            s -> Component.translatable(KEY + "1", heldClause(s.held()), s.completers())),
        new Variant(s -> s.completers() > 0,
            s -> Component.translatable(KEY + "2", heldClause(s.held()), s.completers())),
        // longest single read
        new Variant(s -> s.longestReadMs() > 0,
            s -> Component.translatable(KEY + "3", heldClause(s.held()), duration(s.longestReadMs()))),
        new Variant(s -> s.longestReadMs() > 0,
            s -> Component.translatable(KEY + "4", heldClause(s.held()), duration(s.longestReadMs()))),
        // longest time on one page (+ which page, 1-based)
        new Variant(s -> s.longestPageMs() > 0,
            s -> Component.translatable(KEY + "5", heldClause(s.held()), s.longestPageIndex() + 1,
                duration(s.longestPageMs()))),
        new Variant(s -> s.longestPageMs() > 0,
            s -> Component.translatable(KEY + "6", heldClause(s.held()), s.longestPageIndex() + 1,
                duration(s.longestPageMs()))),
        // total opens
        new Variant(s -> s.opens() > 0,
            s -> Component.translatable(KEY + "7", heldClause(s.held()), timesClause(s.opens()))),
        // page turns
        new Variant(s -> s.pageTurns() > 0,
            s -> Component.translatable(KEY + "8", heldClause(s.held()), timesClause(s.pageTurns()))),
        // re-reads
        new Variant(s -> s.rereads() > 0,
            s -> Component.translatable(KEY + "9", heldClause(s.held()), timesClause(s.rereads()))),
        // held-count only — always eligible, the fallback
        new Variant(s -> true,
            s -> Component.translatable(KEY + "10", heldClause(s.held())))
    );

    /**
     * Build the gray familiar-book line for {@code stats}, choosing a random variant among those whose
     * stat is meaningful. Never returns null — the held-count variant is always eligible.
     */
    public static Component build(BookStatsClient.Stats stats, RandomSource rng) {
        List<Variant> eligible = new ArrayList<>();
        for (Variant v : VARIANTS) if (v.eligible().test(stats)) eligible.add(v);
        Variant chosen = eligible.get(rng.nextInt(eligible.size()));
        return chosen.render().apply(stats).withStyle(ChatFormatting.GRAY);
    }

    // ---- small grammar / formatting helpers -------------------------------------
    // Each returns a translatable sub-component so the client renders it in its own language. English
    // plural/agreement is selected by count here (server-side) and baked into one lang key, since the
    // held count also fixes the verb — languages without agreement map both keys to the same string.

    /** "1 person has" / "N people have" as one unit (person/people + has/have agreement). */
    private static MutableComponent heldClause(int n) {
        return Component.translatable(KEY + (n == 1 ? "held.one" : "held.other"), n);
    }

    /** "1 time" / "N times". */
    private static MutableComponent timesClause(int n) {
        return Component.translatable(KEY + (n == 1 ? "times.one" : "times.other"), n);
    }

    /** Human duration: "45s", "3m 12s", "1h 4m". Sub-second rounds down to "0s". */
    private static MutableComponent duration(long ms) {
        long totalSec = Math.max(0, ms / 1000);
        long h = totalSec / 3600, m = (totalSec % 3600) / 60, s = totalSec % 60;
        if (h > 0) return m > 0 ? Component.translatable(KEY + "dur.hm", h, m)
                                : Component.translatable(KEY + "dur.h", h);
        if (m > 0) return s > 0 ? Component.translatable(KEY + "dur.ms", m, s)
                                : Component.translatable(KEY + "dur.m", m);
        return Component.translatable(KEY + "dur.s", s);
    }
}
