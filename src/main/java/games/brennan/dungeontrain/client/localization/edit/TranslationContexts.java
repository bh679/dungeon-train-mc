package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Where each of DT's lang keys is shown in game, as read from the code — the shipped
 * {@code assets/dungeontrain/translation_contexts.json}, {@code key → [category, …]}.
 *
 * <p>Lets the translation editor place a string (its Preview views, its "From" filters) before
 * this install has ever seen it on screen; the recorders ({@link ButtonKeyRecorder},
 * {@link ChatKeyRecorder}, …) add what is seen in play on top. Only keys whose display call was
 * traced with certainty are in the file — the rest were set aside, listed with the reason in
 * {@code localization/translation-contexts-unsure.json}. Categories with no preview view
 * ({@code screen}, {@code world}, {@code advancement_screen}, {@code toast}, {@code not_shown})
 * are kept for the record and ignored here.</p>
 */
final class TranslationContexts {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PATH = "assets/dungeontrain/translation_contexts.json";

    /** key → the preview views its categories name. */
    private static Map<String, Set<TranslationPreviewKind>> views;

    private TranslationContexts() {}

    /** Whether the code puts {@code key} in {@code view}. */
    static synchronized boolean declares(String key, TranslationPreviewKind view) {
        if (views == null) {
            views = load();
        }
        Set<TranslationPreviewKind> set = key == null ? null : views.get(key);
        return set != null && set.contains(view);
    }

    private static Map<String, Set<TranslationPreviewKind>> load() {
        Map<String, Set<TranslationPreviewKind>> out = new HashMap<>();
        String json = ModJarResources.read(PATH);
        if (json == null) {
            return out;
        }
        try {
            for (Map.Entry<String, JsonElement> entry : JsonParser.parseString(json).getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonArray()) {
                    continue; // "_note" and the like
                }
                Set<TranslationPreviewKind> set = new HashSet<>();
                for (JsonElement category : entry.getValue().getAsJsonArray()) {
                    TranslationPreviewKind kind = viewOf(category.getAsString());
                    if (kind != null) {
                        set.add(kind);
                    }
                }
                if (!set.isEmpty()) {
                    out.put(entry.getKey(), set);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Translations: could not read {} — {}", PATH, e.toString());
        }
        return out;
    }

    /** The preview view a category is, or null for categories with no preview. */
    static TranslationPreviewKind viewOf(String category) {
        return switch (category.toLowerCase(Locale.ROOT)) {
            case "book" -> TranslationPreviewKind.BOOK;
            case "death_screen" -> TranslationPreviewKind.DEATH_SCREEN;
            case "item" -> TranslationPreviewKind.ITEM;
            case "button" -> TranslationPreviewKind.BUTTON;
            case "tooltip" -> TranslationPreviewKind.TOOLTIP;
            case "chat" -> TranslationPreviewKind.CHAT;
            case "action_bar" -> TranslationPreviewKind.ACTION_BAR;
            default -> null; // advancement toasts come from the advancement files themselves
        };
    }
}
