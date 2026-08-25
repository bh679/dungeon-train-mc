package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.net.relay.BookStatsClient;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
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
 * <p>Thirteen variants. A variant is only eligible when the stat it names is meaningful (&gt; 0), so a
 * book no one has finished never shows "0 read it to completion"; variant #10 (held-count only) is always
 * eligible, so there is always a line to show. Variants #11–#13 name the book's 👍/👎 vote tally (the
 * relay returns it in the same author-gated stats payload), eligible once the book has any vote.</p>
 *
 * <p><b>Localization.</b> Each variant, and the count-driven sub-phrases it embeds, is a
 * {@link Component#translatable} key under {@code chat.dungeontrain.familiar_book.*}. The line is built
 * on the server (it picks the random variant and the grammatical-number form by count) but rendered on
 * the client in that client's language — so a Chinese client sees Chinese. Pluralization
 * (person/people, has/have, time/times) is collapsed into single count-selected sub-keys
 * ({@code held.*}, {@code times.*}), whose suffix {@link PluralRules} chooses from the READER's locale:
 * English asks for {@code .one}/{@code .other}, Russian also for {@code .few}/{@code .many}, Japanese
 * only for {@code .other}. That is why every entry point here takes the recipient's locale — the server's
 * own language is not the one the line will be read in.</p>
 */
public final class FamiliarBookMessage {

    private FamiliarBookMessage() {}

    /** Common prefix for every familiar-book lang key. */
    private static final String KEY = "chat.dungeontrain.familiar_book.";

    /** {@code render} takes the reader's locale first, since every count-driven clause needs it. */
    private record Variant(Predicate<BookStatsClient.Stats> eligible,
                           BiFunction<String, BookStatsClient.Stats, MutableComponent> render) {}

    private static final List<Variant> VARIANTS = List.of(
        // read to completion (the base example)
        new Variant(s -> s.completers() > 0,
            (l, s) -> Component.translatable(KEY + "1", heldClause(l, s.held()), s.completers())),
        new Variant(s -> s.completers() > 0,
            (l, s) -> Component.translatable(KEY + "2", heldClause(l, s.held()), s.completers())),
        // longest single read
        new Variant(s -> s.longestReadMs() > 0,
            (l, s) -> Component.translatable(KEY + "3", heldClause(l, s.held()), duration(s.longestReadMs()))),
        new Variant(s -> s.longestReadMs() > 0,
            (l, s) -> Component.translatable(KEY + "4", heldClause(l, s.held()), duration(s.longestReadMs()))),
        // longest time on one page (+ which page, 1-based)
        new Variant(s -> s.longestPageMs() > 0,
            (l, s) -> Component.translatable(KEY + "5", heldClause(l, s.held()), s.longestPageIndex() + 1,
                duration(s.longestPageMs()))),
        new Variant(s -> s.longestPageMs() > 0,
            (l, s) -> Component.translatable(KEY + "6", heldClause(l, s.held()), s.longestPageIndex() + 1,
                duration(s.longestPageMs()))),
        // total opens
        new Variant(s -> s.opens() > 0,
            (l, s) -> Component.translatable(KEY + "7", heldClause(l, s.held()), timesClause(l, s.opens()))),
        // page turns
        new Variant(s -> s.pageTurns() > 0,
            (l, s) -> Component.translatable(KEY + "8", heldClause(l, s.held()), timesClause(l, s.pageTurns()))),
        // re-reads
        new Variant(s -> s.rereads() > 0,
            (l, s) -> Component.translatable(KEY + "9", heldClause(l, s.held()), timesClause(l, s.rereads()))),
        // held-count only — always eligible, the fallback
        new Variant(s -> true,
            (l, s) -> Component.translatable(KEY + "10", heldClause(l, s.held()))),
        // up/down votes — eligible once the book has ANY vote; the sum gate means a book voted only one
        // way still surfaces ("…5 up, 0 down"), which is real information to the author.
        new Variant(s -> s.votesUp() + s.votesDown() > 0,
            (l, s) -> Component.translatable(KEY + "11", heldClause(l, s.held()),
                upClause(l, s.votesUp()), downClause(l, s.votesDown()))),
        new Variant(s -> s.votesUp() + s.votesDown() > 0,
            (l, s) -> Component.translatable(KEY + "12", heldClause(l, s.held()),
                upClause(l, s.votesUp()), downClause(l, s.votesDown()))),
        // compact scoreboard voice — bare "N up, N down".
        new Variant(s -> s.votesUp() + s.votesDown() > 0,
            (l, s) -> Component.translatable(KEY + "13", heldClause(l, s.held()),
                upShort(s.votesUp()), downShort(s.votesDown())))
    );

    /**
     * Build the gray familiar-book line for {@code stats}, choosing a random variant among those whose
     * stat is meaningful. Never returns null — the held-count variant is always eligible.
     *
     * <p>{@code locale} is the RECIPIENT's client language (see {@code WorldInfoReporter.clientLanguage}),
     * used only to pick the grammatical-number form of each count clause; {@code ""} when unknown, which
     * falls back to the English one/other rule.</p>
     */
    public static Component build(String locale, BookStatsClient.Stats stats, RandomSource rng) {
        List<Variant> eligible = new ArrayList<>();
        for (Variant v : VARIANTS) if (v.eligible().test(stats)) eligible.add(v);
        Variant chosen = eligible.get(rng.nextInt(eligible.size()));
        return chosen.render().apply(locale, stats).withStyle(ChatFormatting.GRAY);
    }

    // ---- small grammar / formatting helpers -------------------------------------
    // Each returns a translatable sub-component so the client renders it in its own language. The
    // grammatical-number form is selected by count here (server-side) against the reader's locale and
    // baked into one lang key, since the held count also fixes the verb — languages that don't inflect
    // for number define only the .other form and get it for every count.

    /** "1 person has" / "N people have" as one unit (person/people + has/have agreement). */
    private static MutableComponent heldClause(String locale, int n) {
        return PluralRules.clause(locale, KEY + "held", n);
    }

    /** "1 time" / "N times" — Russian's раз / раза / раз. */
    private static MutableComponent timesClause(String locale, int n) {
        return PluralRules.clause(locale, KEY + "times", n);
    }

    /** "1 reader thumbed it up" / "N readers thumbed it up". */
    private static MutableComponent upClause(String locale, int n) {
        return PluralRules.clause(locale, KEY + "up", n);
    }

    /** "1 thumbed it down" / "N thumbed it down". */
    private static MutableComponent downClause(String locale, int n) {
        return PluralRules.clause(locale, KEY + "down", n);
    }

    /** Compact scoreboard form: "N up" (no singular/plural — one key). */
    private static MutableComponent upShort(int n) {
        return Component.translatable(KEY + "up.short", n);
    }

    /** Compact scoreboard form: "N down" (no singular/plural — one key). */
    private static MutableComponent downShort(int n) {
        return Component.translatable(KEY + "down.short", n);
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
