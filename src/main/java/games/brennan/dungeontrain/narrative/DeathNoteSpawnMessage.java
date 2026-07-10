package games.brennan.dungeontrain.narrative;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

import java.util.List;

/**
 * Dramatic broadcast line shown the moment a Death Note echo spawns to hunt its target — named after
 * the AUTHOR whose vengeful echo has returned (see {@code train.DeathNoteGroupSpawner}). Mirrors
 * {@link SharedBookMessage}: a pool of one-liners picked at random and styled for chat.
 */
public final class DeathNoteSpawnMessage {

    private DeathNoteSpawnMessage() {}

    /** 20 vengeance one-liners; {@code %s} is the author's name (the player whose echo returns). */
    private static final List<String> LINES = List.of(
        "An echo of %s is back with a vengeance.",
        "%s's echo claws its way back from the dead.",
        "The echo of %s returns — and it remembers everything.",
        "%s left unfinished business. Their echo walks the train again.",
        "A vengeful echo of %s stirs among the carriages.",
        "%s's echo rises, hungry for a reckoning.",
        "Death wasn't the end for %s. Their echo hunts anew.",
        "The train shudders — the echo of %s has returned.",
        "An echo of %s emerges from the dark, seeking vengeance.",
        "%s's echo has come to collect a debt.",
        "Something old and angry wearing %s's face stalks the train.",
        "The echo of %s claws back to settle a score.",
        "%s never forgot. Their echo returned to prove it.",
        "A cold wind runs the rails — %s's echo has awoken.",
        "%s's echo steps back through the veil, eyes on its mark.",
        "The dead don't knock — %s's echo is already aboard.",
        "An echo of %s returns, and this time it's personal.",
        "%s's echo has crawled back for one last reckoning.",
        "The carriage darkens as the echo of %s takes form.",
        "%s's vengeful echo has returned to finish what death started."
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

    /** A random spawn announcement naming {@code authorName}, styled dark-red for chat. */
    public static Component random(RandomSource rng, String authorName) {
        return Component.literal(lineFor(rng.nextInt(LINES.size()), authorName))
                .withStyle(ChatFormatting.DARK_RED);
    }
}
