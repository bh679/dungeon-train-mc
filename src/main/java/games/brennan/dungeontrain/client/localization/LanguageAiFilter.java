package games.brennan.dungeontrain.client.localization;

import games.brennan.dungeontrain.client.DungeonTrainLanguages;
import net.minecraft.network.chat.Component;

/**
 * How much of Dungeon Train a language actually has, as the four states the language screen can
 * narrow to. Declaration order is the cycle order.
 *
 * <p><b>They overlap, and must.</b> AI and HUMAN answer two independent questions — "is there
 * machine translation here nobody has read?" and "has a person been through any of this?" — and a
 * language part-way through review answers yes to both. Treating them as exclusive buckets, which
 * this did at first, put zh_cn and zh_tw under HUMAN alone and so hid 232 and 1265 unreviewed lines
 * from the filter whose entire purpose is finding unreviewed lines. Only NONE is exclusive of the
 * other two, because a language the mod ships nothing for has neither.</p>
 *
 * <p>{@link #matches} takes the three answers rather than a locale code so the bucketing is testable
 * without a {@code ResourceManager} — resolving them is {@link #matchesLocale}'s job, and that needs
 * a running client.</p>
 */
public enum LanguageAiFilter {

    /** Every language Minecraft offers, the vanilla list unfiltered. */
    ALL("all"),
    /** Has machine translation still waiting on a human — the languages most in need of a speaker. */
    AI("ai"),
    /** A person has been through some of it, at any depth. */
    HUMAN("human"),
    /** Nothing at all: the player sees the mod in English by vanilla's fallback. */
    NONE("none");

    private final String key;

    LanguageAiFilter(String key) {
        this.key = key;
    }

    public Component label() {
        return Component.translatable("gui.dungeontrain.language.filter." + key);
    }

    /**
     * @param translated    whether the mod ships any translation for the language
     * @param humanReviewed whether a person has been through any of it; moot when not translated
     * @param needsReview   whether any of it is machine translation nobody has read; moot when not
     *                      translated
     */
    public boolean matches(boolean translated, boolean humanReviewed, boolean needsReview) {
        return switch (this) {
            case ALL -> true;
            case AI -> translated && needsReview;
            case HUMAN -> translated && humanReviewed;
            case NONE -> !translated;
        };
    }

    /**
     * The same question against a live locale code, resolved through the two registries.
     *
     * <p>Review is {@link LocalizationCreditRegistry#hasAnyHumanReview} — any of the language, at
     * any depth — not the 90%-coverage {@code isHumanReviewed} the row's badge uses. A filter has to
     * return something: at the coverage bar, "Human reviewed" matches no language the mod ships,
     * including the one a volunteer has taken most of the way. So the two deliberately disagree, and
     * a language can sit under this filter while still wearing the AI badge.</p>
     */
    public boolean matchesLocale(String localeCode) {
        boolean translated = DungeonTrainLanguages.isTranslated(localeCode);
        // Only asked when they can matter: the registry synchronises and parses credit files, and
        // for an untranslated language neither answer changes anything.
        return matches(translated,
            translated && LocalizationCreditRegistry.hasAnyHumanReview(localeCode),
            translated && LocalizationCreditRegistry.hasUnreviewedAi(localeCode));
    }
}
