package games.brennan.dungeontrain.compat;

import games.brennan.discordpresence.survey.SurveyQuestion;
import games.brennan.discordpresence.survey.SurveyRegistry;

/**
 * Registers Dungeon Train's custom feedback-survey questions into Discord Presence's
 * {@link SurveyRegistry}, so they appear — after DP's built-in NPS question — on the
 * death-screen feedback survey. DP drip-feeds one new question per death and never
 * re-asks an answered one.
 *
 * <p>The hard reference to {@code SurveyRegistry} lives only inside {@link #install()},
 * so this class loads even when the survey API is absent; the caller
 * ({@code DungeonTrain.commonSetup}) gates on {@code ModList.isLoaded("discordpresence")}
 * and catches {@link Throwable}, so an older Discord Presence build without the survey
 * API (pre-0.17.0) degrades to "no DT survey questions" instead of crashing mod load.</p>
 */
public final class DiscordSurveyBridge {

    private DiscordSurveyBridge() {}

    /** Register Dungeon Train's survey questions with Discord Presence (requires DP 0.17.0+). */
    public static void install() {
        SurveyRegistry.register(new SurveyQuestion(
                "dungeontrain:difficulty_progression",
                "How is the difficulty progression? (0 = too easy, 5 = just right, 10 = too hard)",
                0, 10, true));
    }
}
