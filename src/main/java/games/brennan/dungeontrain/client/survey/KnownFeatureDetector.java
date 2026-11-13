package games.brennan.dungeontrain.client.survey;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which {@link KnownFeature} notes a feedback answer earns. Pure, so it can be tested without a
 * client; what is installed comes in as {@code available}.
 *
 * <p>Like {@link AiMentionDetector}, a misfire only shows a note the player clicks past, so this
 * leans towards matching. Word boundaries are Latin letters and digits only, so a term still matches
 * when it touches Chinese, Japanese or Korean text with no space between. A feature's skip pattern
 * drops answers from players who already know it ("the edible backpacks aren't enough").</p>
 */
public final class KnownFeatureDetector {

    /** At most this many notes per answer, so a long wish list doesn't bury the death screen. */
    public static final int MAX_NOTES = 2;

    private static final String BEFORE = "(?<![\\p{IsLatin}\\p{N}])";
    private static final String AFTER = "(?![\\p{IsLatin}\\p{N}])";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private record Rule(Pattern match, Pattern skip) {}

    private static final Map<KnownFeature, Rule> RULES = new EnumMap<>(KnownFeature.class);

    static {
        RULES.put(KnownFeature.SAVE_QUIT, rule(
                "save\\s*(?:and|&|n)\\s*quit|quit\\s*(?:and|&)\\s*save|sav(?:e|ing)|checkpoints?"
                        + "|pause\\s+(?:button|the\\s+run)|leave\\s+mid[\\s-]*run|guardar|sauvegard\\p{L}*"
                        + "|speicher\\p{L}*|сохран\\p{L}*",
                "保存|存档|存檔|セーブ|저장",
                null));
        RULES.put(KnownFeature.BACKPACK, rule(
                "backpacks?|rucksacks?|portable\\s+storage|mochilas?|sacs?\\s+[àa]\\s+dos|plecak\\p{L}*|рюкзак\\p{L}*",
                "背包|リュック|배낭",
                "edible|easier\\s+to\\s+find|stack|可堆叠|可食用"));
        RULES.put(KnownFeature.MULTIPLAYER, rule(
                "multi[\\s-]?player|co[\\s-]?op|play(?:ing)?\\s+with\\s+(?:my\\s+)?friends|multijugador"
                        + "|multijoueur|mehrspieler|мультиплеер\\p{L}*",
                "多人|联机|聯機|マルチ|멀티",
                null));
        RULES.put(KnownFeature.SORTING, rule(
                "(?:inventory|auto)[\\s-]*sort\\p{L}*|sort(?:ing)?\\s+(?:my\\s+|the\\s+)?inventory"
                        + "|inventory\\s+manag\\p{L}*|ordenar\\s+(?:el\\s+)?inventario|tri\\s+(?:de\\s+l'|d')?inventaire"
                        + "|сортировк\\p{L}*",
                "一键整理|一鍵整理|整理物品|整理背包|自动整理",
                null));
        RULES.put(KnownFeature.TRASH_SLOT, rule(
                "trash[\\s-]*(?:slot|can|bin)s?|trashslot|delete\\s+items?|basura|poubelle|lixeira|m[üu]lleimer"
                        + "|мусор\\p{L}*",
                "垃圾桶|垃圾箱|删除物品|ゴミ箱|휴지통",
                null));
        RULES.put(KnownFeature.JEI, rule(
                "jei|just\\s+enough\\s+items|recipe\\s+(?:book|viewer|lookup)|what\\s+i\\s+can\\s+craft",
                "配方查询|合成表",
                null));
    }

    private KnownFeatureDetector() {}

    private static Rule rule(String latinTerms, String unspacedTerms, String skip) {
        Pattern match = Pattern.compile(BEFORE + "(?:" + latinTerms + ")" + AFTER + "|" + unspacedTerms, FLAGS);
        return new Rule(match, skip == null ? null : Pattern.compile(skip, FLAGS));
    }

    /**
     * The notes an answer earns, in {@link KnownFeature} order, at most {@link #MAX_NOTES}.
     *
     * @param npsScore  the 0-10 score when this is the NPS question, else {@code -1}; only that
     *                  question can earn the AI note
     * @param comment   the typed answer or comment
     * @param language  a locale such as "en_us", for {@link AiMentionDetector}
     * @param available the features this install can answer (TrashSlot loaded, the pack's mods, ...)
     */
    public static List<KnownFeature> detect(int npsScore, String comment, String language,
                                            Set<KnownFeature> available) {
        List<KnownFeature> out = new ArrayList<>();
        if (comment == null || comment.isBlank()) return out;
        for (KnownFeature feature : KnownFeature.values()) {
            if (out.size() >= MAX_NOTES) break;
            if (!available.contains(feature)) continue;
            if (matches(feature, npsScore, comment, language)) out.add(feature);
        }
        return List.copyOf(out);
    }

    private static boolean matches(KnownFeature feature, int npsScore, String comment, String language) {
        if (feature == KnownFeature.AI) return AiMentionDetector.shouldShowNote(npsScore, comment, language);
        Rule rule = RULES.get(feature);
        if (rule == null || !rule.match().matcher(comment).find()) return false;
        return rule.skip() == null || !rule.skip().matcher(comment).find();
    }
}
