package games.brennan.dungeontrain.client;

import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.ClientDisplayConfig.PoliticalFilter;
import games.brennan.dungeontrain.narrative.LanguageFamily;
import net.minecraft.client.Minecraft;

/**
 * Client-side resolution of the Political Filter preference — the ONE place the "not answered yet"
 * default lives, so the prompt, the options row and the packet sent to the server can never disagree
 * about what a given player's answer means.
 *
 * <h3>Who is offered the choice</h3>
 * <p>Players on a Chinese-language client. Community content the relay tagged {@code political} is
 * content that could get a stream taken down or a video censored in mainland China — a risk that is
 * about the reader's situation, not the writing. So the choice is offered where it is a live concern
 * and left alone everywhere else, rather than filtering by fiat or not offering it at all.</p>
 *
 * <p>Scoped to the whole {@code zh} family via {@link LanguageFamily} (zh_cn / zh_tw / zh_hk / lzh),
 * matching {@code PaymentLinks.isChineseLocale} and the relay's own language-family grouping, so a
 * player's books and their filter prompt agree on what "Chinese client" means.</p>
 *
 * <h3>What UNSET means</h3>
 * <p>{@link PoliticalFilter#UNSET} resolves to ON for those players and OFF for everyone else. The
 * asymmetry is the point: a player who has not answered yet — including one who dismissed the prompt
 * with Esc before {@link #answer} recorded a choice — should not be exposed to content they might
 * have asked to filter, while nobody outside that audience has their loot quietly narrowed by a
 * question they were never asked.</p>
 */
public final class PoliticalFilterPrefs {

    private PoliticalFilterPrefs() {}

    /** The selected client locale, or {@code null} when the client isn't far enough along to have one. */
    private static String selectedLocale() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.getLanguageManager() == null ? null : mc.getLanguageManager().getSelected();
    }

    /** Whether this client's language is in the Chinese family — the audience offered the choice. */
    public static boolean isChineseLocale() {
        String locale = selectedLocale();
        return locale != null && "zh".equals(LanguageFamily.of(locale));
    }

    /**
     * Whether tagged content should be filtered out for this player right now. {@code UNSET} resolves
     * against the client language — see the class javadoc.
     */
    public static boolean isEnabled() {
        PoliticalFilter stored = ClientDisplayConfig.getPoliticalFilter();
        if (stored == PoliticalFilter.ON) return true;
        if (stored == PoliticalFilter.OFF) return false;
        return isChineseLocale();
    }

    /**
     * Whether the one-time prompt should be shown.
     *
     * <p>Gated on network consent as well as the locale: without it the mod fetches no community
     * content at all, so there is nothing to filter and the question would be noise on top of the
     * consent screen the player is already answering. If they grant consent later, the prompt is
     * waiting for them the next time they reach the title screen.</p>
     */
    public static boolean shouldPrompt() {
        return ClientDisplayConfig.getPoliticalFilter() == PoliticalFilter.UNSET
                && isChineseLocale()
                && DiscordPresenceClientConfig.isGranted();
    }

    /** Record the player's answer and push it to the server so it applies to the current session. */
    public static void answer(boolean filterEnabled) {
        ClientDisplayConfig.setPoliticalFilter(filterEnabled ? PoliticalFilter.ON : PoliticalFilter.OFF);
        PoliticalFilterSyncClient.syncNow();
    }
}
