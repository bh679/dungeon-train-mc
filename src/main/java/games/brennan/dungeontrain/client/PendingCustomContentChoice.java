package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.world.CustomContentChoice;

/**
 * Client-side static holder for the answer to the custom-content question, given <b>before</b> the
 * world exists. Read on {@code LevelEvent.Load} for the overworld
 * ({@code WorldLifecycleEvents.onOverworldLoad}) and committed into
 * {@link games.brennan.dungeontrain.world.DungeonTrainWorldData}, then cleared — the same shape,
 * and the same drain point, as {@link PendingWorldChoices}.
 *
 * <p><b>Why it has to be answered this early.</b> A world running Train Editor content is Free
 * Play, and the answer decides whether it runs that content at all. Asked at join — where the
 * question used to live — the world has already generated its spawn region from that content and
 * the player has already been put into Free Play, so "no thanks, run without my changes" arrives
 * after the thing it was declining. Committed at overworld load it lands before {@code
 * prepareLevels}, which is early enough that a declining world never stamps a single carriage from
 * custom templates.</p>
 *
 * <p>Deliberately <b>not</b> cleared on logout, unlike {@link PendingWorldChoices}: a reboard tears
 * the old world down between the answer and the new world's load, so a logout-clear would drop
 * exactly the answer it was meant to carry. It is instead overwritten on each ask and consumed at
 * the drain point — and only ever applied to a world that hasn't already answered for itself, so a
 * value left over from an abandoned world creation can't reach a world that has its own decision.</p>
 *
 * <p>Client-only — never referenced from a class loaded on a dedicated server, where the question
 * is asked at join instead ({@code CustomContentPromptEvents}).</p>
 */
public final class PendingCustomContentChoice {

    private static volatile CustomContentChoice choice;

    private PendingCustomContentChoice() {}

    public static void set(CustomContentChoice choice) {
        PendingCustomContentChoice.choice = choice;
    }

    public static boolean isPresent() {
        return choice != null && choice.isAnswered();
    }

    /** The answer. Only meaningful when {@link #isPresent()}. */
    public static CustomContentChoice get() {
        return choice;
    }

    public static void clear() {
        choice = null;
    }
}
