package games.brennan.dungeontrain.narrative;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

import java.util.List;

/**
 * Warm broadcast line shown the moment a Love Note echo arrives for its target — named after the
 * AUTHOR whose echo has come looking (see {@code event.DeathNoteRefreshEvents}). The mirror of
 * {@link DeathNoteSpawnMessage}, in the same shape: a pool of one-liners picked at random and styled
 * for chat, pink instead of dark red.
 */
public final class LoveNoteSpawnMessage {

    private LoveNoteSpawnMessage() {}

    /**
     * 20 arrival one-liners; {@code %s} is the author's name (the player whose echo returns).
     *
     * <p>Deliberately <em>not</em> the vengeance pool softened — an earlier draft inverted
     * {@link DeathNoteSpawnMessage} line for line and read as "the same sentence, but nicer".
     * These put the love in the foreground and say what the echo <em>is</em> rather than what it
     * isn't. The broadcast is server-wide, so a few lines are written for the bystander rather
     * than the target: the sting for them is that the love is real and it isn't theirs.</p>
     */
    private static final List<String> LINES = List.of(
        "%s's echo is aboard, and it comes in love.",
        "An echo of %s steps aboard with its arms full.",
        "%s died loving someone. The echo carries it.",
        "An echo of %s walks the carriages, looking for someone it loves.",
        "%s's echo has waited here a long time to love someone. Not you.",
        "The echo of %s arrives with gifts it will not be talked out of.",
        "%s's echo boards in silence, in love, and looking.",
        "Somewhere ahead, %s's echo has found the one it loved.",
        "An echo of %s stands up. It has been in love since it died.",
        "%s's echo remembers who it loved, and nothing else.",
        "Love outlived %s. Their echo brought it aboard.",
        "%s left love behind instead of a grudge. The echo came to deliver it.",
        "An echo of %s walks the line, giving away everything it has.",
        "%s's echo wants nothing from you. It already loves someone else.",
        "The echo of %s came a long way to love someone in person.",
        "%s's echo has arrived, and for once the train brought love.",
        "Something of %s stayed on the tracks. Love kept it there.",
        "An echo of %s is here, in love. Let it through.",
        "%s's echo has come back to finish loving someone.",
        "The dead rarely come back gently. %s's echo came back in love."
    );

    /** How many distinct lines exist (for tests + callers). */
    public static int variantCount() {
        return LINES.size();
    }

    /** Format line {@code index} with {@code authorName} (blank → "someone"). Package-private for tests. */
    static String lineFor(int index, String authorName) {
        String name = (authorName == null || authorName.isBlank()) ? "someone" : authorName;
        return String.format(LINES.get(index), name);
    }

    /** A random arrival announcement naming {@code authorName}, styled pink for chat. */
    public static Component random(RandomSource rng, String authorName) {
        return Component.literal(lineFor(rng.nextInt(LINES.size()), authorName))
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }
}
