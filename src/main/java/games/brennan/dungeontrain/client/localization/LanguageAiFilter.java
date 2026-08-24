package games.brennan.dungeontrain.client.localization;

import games.brennan.dungeontrain.client.DungeonTrainLanguages;
import net.minecraft.network.chat.Component;

/**
 * How much of Dungeon Train a language actually has, as the four buckets the language screen can
 * narrow to. Declaration order is the cycle order.
 *
 * <p>The three states are exactly what the row already draws — the logo means "the mod ships this
 * language", the ring and the blue {@code AI} badge mean "and nobody has reviewed it". Deriving the
 * filter from the same two questions is what stops badge and filter ever disagreeing; a second
 * notion of "translated" living here would drift from the first the moment either moved.</p>
 *
 * <p>{@link #matches} takes the two answers rather than a locale code so the bucketing is testable
 * without a {@code ResourceManager} — resolving them is {@link #matchesLocale}'s job, and that needs
 * a running client.</p>
 */
public enum LanguageAiFilter {

    /** Every language Minecraft offers, the vanilla list unfiltered. */
    ALL("all"),
    /** Machine-translated, nobody has read it — the languages most in need of a speaker. */
    AI("ai"),
    /** A human has been through it. */
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
     * @param translated     whether the mod ships any translation for the language
     * @param humanReviewed  whether a person has reviewed it; meaningless when not translated
     */
    public boolean matches(boolean translated, boolean humanReviewed) {
        return switch (this) {
            case ALL -> true;
            case AI -> translated && !humanReviewed;
            case HUMAN -> translated && humanReviewed;
            case NONE -> !translated;
        };
    }

    /** The same question against a live locale code, resolved through the two registries. */
    public boolean matchesLocale(String localeCode) {
        boolean translated = DungeonTrainLanguages.isTranslated(localeCode);
        // Only asked when it can matter: isHumanReviewed synchronises and parses credit files, and
        // for an untranslated language the answer changes nothing.
        return matches(translated,
            translated && LocalizationCreditRegistry.isHumanReviewed(localeCode));
    }
}
