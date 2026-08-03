package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.net.relay.SharedCarriageClient.Credits;
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
    public static Component creditLine(Credits credits, RandomSource rng) {
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
            out.append(Component.translatable(key, editorList(editors, credits.editorCount())));
        }
        return out.withStyle(ChatFormatting.GRAY);
    }

    /** The named editors joined with ", ", wrapped in "and N others" when the list was truncated. */
    private static Component editorList(List<String> editors, int total) {
        MutableComponent joined = Component.empty();
        for (int i = 0; i < editors.size(); i++) {
            if (i > 0) joined.append(", ");
            joined.append(name(editors.get(i)));
        }
        int extra = total - editors.size();
        if (extra <= 0) return joined;
        return Component.translatable("chat.dungeontrain.shared_carriage.credit.more", joined, extra);
    }

    /** One contributor's name. Unstyled on purpose — it inherits the line's colour like any other word. */
    private static Component name(String raw) {
        return Component.literal(raw);
    }
}
