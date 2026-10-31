package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.client.LoadingStories;
import games.brennan.dungeontrain.narrative.PluralRules;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * The lines the death perks menu tells as it builds up, one per {@link MenuReveal} beat, and how long
 * each holds: the loading screen's rule ({@link LoadingStories#lineMs}), longer for a longer line.
 *
 * <ol>
 *   <li>the advancements earned this life (left out, at the shortest hold, with no stats from the server)</li>
 *   <li>the unlock points they earned, a line on its own (left out with no stats)</li>
 *   <li>the points put into perks this life (left out, and its beat skipped, with none)</li>
 *   <li>this can help the next life (the unlock points, shown in the centre)</li>
 *   <li>perks unlock more play styles</li>
 *   <li>select a perk to see what it does; stays until they hover one</li>
 * </ol>
 *
 * @param lines one per beat; null where that beat has no line
 * @param beats each beat's time on screen, ms
 */
record DeathStory(List<Component> lines, long[] beats) {

    static final String KEY = "gui.dungeontrain.perks.story.";

    /**
     * @param stats     this life's {advancements that earn unlock points, unlock points they earned}, or
     *                  null when the server sent none
     * @param atDeath   the perks as the player died (their points this life)
     * @param locale    the client's language, for the plural forms
     */
    static DeathStory of(int @Nullable [] stats, PerkMenuSnapshot atDeath, String locale) {
        Component[] lines = new Component[MenuReveal.BEATS];
        if (stats != null) {
            lines[MenuReveal.BEAT_STARS] = Component.translatable(KEY + "earned",
                PluralRules.clause(locale, KEY + "advancements", stats[0]));
            lines[MenuReveal.BEAT_EARNED] = Component.translatable(KEY + "earned_points",
                PluralRules.clause(locale, KEY + "points", stats[1]));
        }
        int ranks = atDeath.ranksBought();
        if (ranks > 0) {
            long perks = atDeath.active().values().stream().filter(n -> n > 0).count();
            lines[MenuReveal.BEAT_ACTIVE] = Component.translatable("gui.dungeontrain.perks.death.question", ranks, perks);
        }
        lines[MenuReveal.BEAT_CENTRE] = Component.translatable(KEY + "next_life");
        lines[MenuReveal.BEAT_PERKS] = Component.translatable(KEY + "unlock");
        lines[MenuReveal.BEAT_SELECT] = Component.translatable(KEY + "select");
        long[] beats = new long[MenuReveal.BEATS];
        for (int i = 0; i < beats.length; i++) {
            if (lines[i] != null) beats[i] = LoadingStories.lineMs(lines[i].getString());
            else beats[i] = i == MenuReveal.BEAT_ACTIVE || i == MenuReveal.BEAT_EARNED ? 0 : LoadingStories.minLineMs();
        }
        return new DeathStory(java.util.Collections.unmodifiableList(Arrays.asList(lines)), beats);
    }

    /** The line for beat {@code i}, or null. */
    @Nullable Component line(int i) {
        return i < 0 || i >= lines.size() ? null : lines.get(i);
    }
}
