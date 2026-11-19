package games.brennan.dungeontrain.client.survey;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whether an NPS answer is a low score that blames AI, which earns the player a note from Brennan
 * ({@link KnownFeature#AI}, shown by {@link KnownFeatureNotes}). Pure, so it can be tested without a client.
 *
 * <p>A misfire only shows a polite note, so this leans towards matching, with three guards against
 * the misfires that would be common:</p>
 * <ul>
 *   <li>Word boundaries are Latin letters and digits only, not all letters, so {@code AI} still
 *       matches when it touches Chinese, Japanese or Korean text with no space between.</li>
 *   <li>{@code ai} is an everyday word in Italian ("to the"), Romanian ("you have") and Portuguese
 *       ("ouch"), so in those languages only the capitalised {@code AI} counts.</li>
 *   <li>"Mob AI" and the like are about how mobs behave, so those phrases are removed first.</li>
 * </ul>
 */
public final class AiMentionDetector {

    /** The note goes to scores strictly below this, so 0-4 on the 0-10 scale. */
    static final int LOW_SCORE_BELOW = 5;

    private static final String BEFORE = "(?<![\\p{IsLatin}\\p{N}])";
    private static final String AFTER = "(?![\\p{IsLatin}\\p{N}])";

    /** Languages where lowercase {@code ai} is an ordinary word. */
    private static final Set<String> LOWERCASE_AI_IS_A_WORD = Set.of("it", "ro", "pt");

    /** Gameplay AI: "mob AI", "the villagers' AI", "mob-KI", "AI of the mobs". */
    private static final Pattern GAMEPLAY_AI = Pattern.compile(
            BEFORE + "(?:(?:mob|enemy|enemie|villager|npc|zombie|skeleton|monster|creature|pathfinding)s?['’]?s?"
                    + "[\\s-]*(?:ai|a\\.i\\.?|ia|ki)"
                    + "|(?:ai|a\\.i\\.?)\\s+of\\s+(?:the\\s+)?"
                    + "(?:mob|enem(?:y|ie)|villager|npc|zombie|skeleton|monster|creature)s?)" + AFTER,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Any-case terms that mean AI in every language. */
    private static final Pattern ANY_CASE_TERMS = Pattern.compile(
            BEFORE + "(?:a\\.i\\.?|artificial\\s+intelligence|chat\\s?gpt|gpt(?:-?\\d\\w*)?|llms?|claude|gemini"
                    + "|copilot|midjourney|inteligencia\\s+artificial|inteligência\\s+artificial"
                    + "|intelligence\\s+artificielle|intelligenza\\s+artificiale|künstliche\\s+intelligenz"
                    + "|kunstmatige\\s+intelligentie|sztuczn\\p{L}+\\s+inteligencj\\p{L}+|kecerdasan\\s+buatan"
                    + "|inteligență\\s+artificială|trí\\s+tuệ\\s+nhân\\s+tạo"
                    + "|искусственн\\p{L}+\\s+интеллект\\p{L}*|штучн\\p{L}+\\s+інтелект\\p{L}*)" + AFTER,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern AI_ANY_CASE = Pattern.compile(BEFORE + "ai" + AFTER, Pattern.CASE_INSENSITIVE);

    /**
     * Capitals only: {@code ia} is an Indonesian word, {@code ki} a Japanese romanisation and
     * {@code ии} a Russian word ending, while their capitalised forms are the abbreviation for AI.
     */
    private static final Pattern CAPITALS_ONLY = Pattern.compile(
            BEFORE + "(?:AI|IA|KI)" + AFTER + "|(?<!\\p{IsCyrillic})(?:ИИ|ШІ)(?!\\p{IsCyrillic})");

    /** Scripts with no capitals, matched anywhere. */
    private static final Pattern NO_CAPITALS_TERMS = Pattern.compile("人工智能|人工智慧|人工知能|인공지능|ปัญญาประดิษฐ์");

    private AiMentionDetector() {}

    /** True for a score of 0-4 with a comment that mentions AI. {@code language} is a locale such as "en_us". */
    public static boolean shouldShowNote(int score, String comment, String language) {
        return score >= 0 && score < LOW_SCORE_BELOW && mentionsAi(comment, language);
    }

    static boolean mentionsAi(String comment, String language) {
        if (comment == null || comment.isBlank()) return false;
        String text = GAMEPLAY_AI.matcher(comment).replaceAll(" ");
        if (ANY_CASE_TERMS.matcher(text).find()
                || CAPITALS_ONLY.matcher(text).find()
                || NO_CAPITALS_TERMS.matcher(text).find()) {
            return true;
        }
        return !lowercaseAiIsAWord(language) && AI_ANY_CASE.matcher(text).find();
    }

    private static boolean lowercaseAiIsAWord(String language) {
        if (language == null) return false;
        String lower = language.toLowerCase(Locale.ROOT);
        int separator = lower.indexOf('_');
        return LOWERCASE_AI_IS_A_WORD.contains(separator < 0 ? lower : lower.substring(0, separator));
    }
}
