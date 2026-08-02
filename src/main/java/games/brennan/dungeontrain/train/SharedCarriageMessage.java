package games.brennan.dungeontrain.train;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

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
}
