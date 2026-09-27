package games.brennan.dungeontrain.cheat;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The two shared sentences that say what Free Play means, so translators write them once and
 * every screen that explains Free Play says the same thing.
 *
 * <p>{@link #consequence()} is for a run that is already in Free Play (chat notice, effect
 * tooltip, confirm screen). {@link #explained()} adds the "safe space" line and is for a choice
 * still ahead of the player — screens that embed it carry a single {@code %s} for it.</p>
 */
public final class FreePlayText {

    /** "Advancements &amp; stats won't be saved." */
    public static final String KEY_CONSEQUENCE = "effect.dungeontrain.free_play.desc.1";
    /** "A safe space to use hacks and cheats." */
    public static final String KEY_SAFE_SPACE = "gui.dungeontrain.free_play.safe_space";
    /** {@code "%s %s"} — consequence then safe space; the join is per-locale. */
    public static final String KEY_EXPLAINED = "gui.dungeontrain.free_play.explained";

    private FreePlayText() {}

    public static MutableComponent consequence() {
        return Component.translatable(KEY_CONSEQUENCE);
    }

    public static MutableComponent explained() {
        return Component.translatable(KEY_EXPLAINED,
                consequence(), Component.translatable(KEY_SAFE_SPACE));
    }

    /** A context string whose one {@code %s} is the full Free Play explanation. */
    public static MutableComponent withExplanation(String contextKey) {
        return Component.translatable(contextKey, explained());
    }
}
