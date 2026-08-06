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

    /** 20 arrival one-liners; {@code %s} is the author's name (the player whose echo returns). */
    private static final List<String> LINES = List.of(
        "An echo of %s has come back for someone.",
        "%s's echo walks the train again — gently, this time.",
        "The echo of %s returns, and it remembers kindly.",
        "%s left something unsaid. Their echo came to say it.",
        "A warm echo of %s stirs among the carriages.",
        "%s's echo rises, looking for a familiar face.",
        "Death wasn't the end for %s. Their echo came back anyway.",
        "The train softens — the echo of %s has returned.",
        "An echo of %s steps out of the dark, hands open.",
        "%s's echo has come to repay a kindness.",
        "Something old and gentle wearing %s's face walks the train.",
        "The echo of %s crossed back for one more meeting.",
        "%s never forgot a friend. Their echo returned to prove it.",
        "A warm draught runs the rails — %s's echo has awoken.",
        "%s's echo steps back through the veil, searching the carriages.",
        "The dead don't knock — %s's echo is already aboard, waiting.",
        "An echo of %s returns, and this time it's fond.",
        "%s's echo has crawled back for one last kindness.",
        "The carriage warms as the echo of %s takes form.",
        "%s's echo has returned to finish what death interrupted."
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
