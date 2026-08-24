package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.narrative.PluralRules;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.Credits;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.Deaths;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Dark-gray flavour chat line shown the moment a player steps onto a shared carriage — the same muted
 * "no one is here to answer" styling as {@link games.brennan.dungeontrain.narrative.SharedBookMessage}
 * (the book-burn / vote line). Two pools, chosen by whether the carriage is a fresh local canvas or a
 * build leased from the relay pool:
 *
 * <ul>
 *   <li>{@link #newCarriage} — a fresh shared carriage; the lines hint, indirectly, that it invites
 *       change.</li>
 *   <li>{@link #seenCarriage} — a carriage someone else authored; the lines hint that another traveller
 *       has been here before.</li>
 *   <li>{@link #ownCarriage} — a build by someone in this world, come back around; the lines hint at
 *       recognising your own work.</li>
 * </ul>
 *
 * <p>Each line is a {@link Component#translatable} template with a {@code %s} slot filled by a randomly
 * chosen noun ({@code room / carriage / cart / space}), so every message reads a little differently and
 * the client renders it in its own language. Keys: {@code chat.dungeontrain.shared_carriage.new.1..N},
 * {@code .seen.1..N}, and the nouns {@code .noun.1..4}.</p>
 *
 * <p>{@link #deathLine} adds an optional THIRD line, naming travellers who died aboard. It has the same
 * null contract as {@link #creditLine} and for the same reason: a carriage nobody has died in must read
 * exactly as it always has, with no line and no pause where one would have been.</p>
 *
 * <p>{@link #creditLine} adds an optional SECOND, dimmer line naming who built the carriage. It is
 * deliberately separate from the flavour lines above: a list of names doesn't fold gracefully into six
 * different prose variants, and keeping it apart means a carriage with no names to show (anything stored
 * before contributor names existed) renders exactly as it always did.</p>
 */
public final class SharedCarriageMessage {

    private SharedCarriageMessage() {}

    /** Distinct "invites change" lines keyed {@code chat.dungeontrain.shared_carriage.new.1..NEW_LINES}. */
    private static final int NEW_LINES = 6;
    /** Distinct "someone was here" lines keyed {@code chat.dungeontrain.shared_carriage.seen.1..SEEN_LINES}. */
    private static final int SEEN_LINES = 6;
    /** Distinct "you built this" lines keyed {@code chat.dungeontrain.shared_carriage.own.1..OWN_LINES}. */
    private static final int OWN_LINES = 6;
    /** Nouns keyed {@code chat.dungeontrain.shared_carriage.noun.1..NOUNS} (room / carriage / cart / space). */
    private static final int NOUNS = 4;

    /** A random "this is a fresh carriage that invites change" line, gray. */
    public static Component newCarriage(RandomSource rng) {
        return line("new", NEW_LINES, rng);
    }

    /** A random "someone else has built here before you" line, gray. */
    public static Component seenCarriage(RandomSource rng) {
        return line("seen", SEEN_LINES, rng);
    }

    /** A random "this one came back around to you" line, gray. */
    public static Component ownCarriage(RandomSource rng) {
        return line("own", OWN_LINES, rng);
    }

    private static Component line(String group, int count, RandomSource rng) {
        int n = rng.nextInt(count) + 1;
        int nounIdx = rng.nextInt(NOUNS) + 1;
        Component noun = Component.translatable("chat.dungeontrain.shared_carriage.noun." + nounIdx);
        return Component.translatable("chat.dungeontrain.shared_carriage." + group + "." + n, noun)
                .withStyle(ChatFormatting.GRAY);
    }

    /** Ways to name the original builder, keyed {@code ….credit.creator.1..CREDIT_CREATOR_LINES}. */
    private static final int CREDIT_CREATOR_LINES = 4;
    /** Ways to name later editors AFTER a creator clause, keyed {@code ….credit.editors.1..N}. */
    private static final int CREDIT_EDITOR_LINES = 4;
    /** Ways to name editors with NO creator clause in front, keyed {@code ….credit.editors_alone.1..N}. */
    private static final int CREDIT_EDITORS_ALONE_LINES = 2;

    /**
     * The "It began with X. After them, Y and Z." credit, or {@code null} when there is nobody to name —
     * an older relay, a build stored before names were captured, or contributors who never granted
     * network consent. Callers send it as a second chat line only when it is non-null, so those carriages
     * keep the plain flavour line they have always shown.
     *
     * <p>Randomised like the flavour lines above it, so the pair doesn't settle into one fixed phrasing.
     * The editor clause has two families: one that follows a creator ("After them, …") and one that
     * stands alone, for the case where editors are named but the original builder is not.</p>
     *
     * <p>Rendered in the same gray as the flavour line it follows — names included, deliberately flat,
     * so the pair reads as one thought rather than a message with a highlighted data field in it. At
     * most five editors are named; any beyond that collapse into "and N others besides".</p>
     */
    @Nullable
    public static Component creditLine(String locale, Credits credits, RandomSource rng) {
        if (credits == null || !credits.hasAny()) return null;
        MutableComponent out = Component.empty();
        boolean hasCreator = !credits.creator().isEmpty();
        if (hasCreator) {
            out.append(Component.translatable(
                    "chat.dungeontrain.shared_carriage.credit.creator." + (rng.nextInt(CREDIT_CREATOR_LINES) + 1),
                    name(credits.creator())));
        }
        List<String> editors = credits.editors();
        if (!editors.isEmpty()) {
            // "After them, …" only makes sense once someone has been named; otherwise it dangles.
            String key = hasCreator
                    ? "chat.dungeontrain.shared_carriage.credit.editors." + (rng.nextInt(CREDIT_EDITOR_LINES) + 1)
                    : "chat.dungeontrain.shared_carriage.credit.editors_alone." + (rng.nextInt(CREDIT_EDITORS_ALONE_LINES) + 1);
            if (hasCreator) out.append(" ");
            out.append(Component.translatable(key, editorList(locale, editors, credits.editorCount())));
        }
        return out.withStyle(ChatFormatting.GRAY);
    }

    /** The named editors joined with ", ", wrapped in "and N others" when the list was truncated. */
    private static Component editorList(String locale, List<String> editors, int total) {
        MutableComponent joined = Component.empty();
        for (int i = 0; i < editors.size(); i++) {
            if (i > 0) joined.append(", ");
            joined.append(name(editors.get(i)));
        }
        int extra = total - editors.size();
        if (extra <= 0) return joined;
        return Component.translatable("chat.dungeontrain.shared_carriage.credit.more", joined,
                PluralRules.clause(locale, "chat.dungeontrain.shared_carriage.credit.more.count", extra));
    }

    /** One contributor's name. Unstyled on purpose — it inherits the line's colour like any other word. */
    private static Component name(String raw) {
        return Component.literal(raw);
    }

    /** Ways to name a single traveller who died here, keyed {@code ….deaths.one.1..N}. */
    private static final int DEATH_ONE_LINES = 4;
    /** Ways to name several, keyed {@code ….deaths.few.1..N}. */
    private static final int DEATH_FEW_LINES = 4;
    /** Ways to say SEVERAL deaths happened with nobody to name, keyed {@code ….deaths.unnamed.1..N}. */
    private static final int DEATH_UNNAMED_LINES = 3;
    /**
     * The same for exactly one, keyed {@code ….deaths.unnamed_one.1..N}. Its own family because the
     * plural lines take a count argument and read "1 travellers" at one — a count of one is better said
     * in words than substituted into a sentence built for many.
     */
    private static final int DEATH_UNNAMED_ONE_LINES = 3;

    /**
     * The "N travellers ended here" line, or {@code null} when this carriage has no deaths on record —
     * a fresh build, a relay too old to send them, or simply a carriage everyone walked back out of.
     * Callers send it only when non-null, so a clean carriage keeps exactly the message it showed before
     * the death log existed.
     *
     * <p>Three phrasing families, by what there is to say:</p>
     * <ul>
     *   <li>one named traveller — the specific, and the bleakest;</li>
     *   <li>several — names up to five, the rest collapsing into "and N others besides", reusing the
     *       credit line's {@code .more} wrapper;</li>
     *   <li>deaths but no names — every one of them was a player without network consent, so the count
     *       is all that can honestly be said. Exactly one gets its own family, since the plural lines
     *       substitute a number and would read "1 travellers".</li>
     * </ul>
     *
     * <p>Note the count is the RELAY's uncapped total, not the length of the name list: a carriage that
     * has outlived its own trail still reports how many died, and only names who it can.</p>
     */
    @Nullable
    public static Component deathLine(String locale, Deaths deaths, RandomSource rng) {
        if (deaths == null || !deaths.hasAny()) return null;
        List<String> names = deaths.names();
        int total = deaths.total();
        if (names.isEmpty()) {
            return total == 1
                    ? Component.translatable("chat.dungeontrain.shared_carriage.deaths.unnamed_one."
                            + (rng.nextInt(DEATH_UNNAMED_ONE_LINES) + 1)).withStyle(ChatFormatting.GRAY)
                    : unnamedMany(locale, total, rng.nextInt(DEATH_UNNAMED_LINES) + 1);
        }
        // "one" is only honest when exactly one death is on record — a single NAME with a higher total
        // means the others simply couldn't be named, which is the plural case wearing one name.
        if (total == 1) {
            return Component.translatable(
                    "chat.dungeontrain.shared_carriage.deaths.one." + (rng.nextInt(DEATH_ONE_LINES) + 1),
                    name(names.get(0))).withStyle(ChatFormatting.GRAY);
        }
        return Component.translatable(
                "chat.dungeontrain.shared_carriage.deaths.few." + (rng.nextInt(DEATH_FEW_LINES) + 1),
                countClause(locale, "travellers", total), editorList(locale, names, total))
                .withStyle(ChatFormatting.GRAY);
    }

    /**
     * One of the three "deaths happened, nobody to name" lines. Each names a DIFFERENT noun — travellers,
     * runs, times — so each takes its own count clause rather than a bare number: a language that declines
     * its nouns declines each of them separately, and cannot do so at all if the noun is stranded in the
     * outer sentence.
     */
    private static Component unnamedMany(String locale, int total, int variant) {
        String noun = switch (variant) {
            case 2 -> "runs";
            case 3 -> "times";
            default -> "travellers";
        };
        return Component.translatable("chat.dungeontrain.shared_carriage.deaths.unnamed." + variant,
                countClause(locale, noun, total)).withStyle(ChatFormatting.GRAY);
    }

    /**
     * "{@code N travellers}" / "{@code N runs}" / "{@code N times}" as a nested component, in the form
     * {@code locale} wants for {@code n} — the same pattern {@code FamiliarBookMessage.heldClause} uses.
     * Keeping the counted noun OUT of the surrounding sentence is what lets Russian say
     * "2 путника" but "5 путников" without four re-phrasings of every line.
     */
    private static Component countClause(String locale, String noun, int n) {
        return PluralRules.clause(locale, "chat.dungeontrain.shared_carriage.deaths.count." + noun, n);
    }
}
