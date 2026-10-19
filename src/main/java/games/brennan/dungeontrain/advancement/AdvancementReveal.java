package games.brennan.dungeontrain.advancement;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The chat line a player gets after earning an advancement, naming the advancements that earn just
 * revealed: those their advancement screen shows for the first time and that they haven't earned yet.
 *
 * <p>Which advancements became visible is decided by the server's own visibility pass (vanilla plus
 * {@code AdvancementVisibilityEvaluatorMixin}); {@code PlayerAdvancementsRehomeMixin} hands the newly
 * visible set to {@link #select} and sends {@link #message} to that player only.</p>
 */
public final class AdvancementReveal {

    /** Names listed in full before the rest collapse into "+N more". */
    public static final int MAX_NAMED = 6;

    // Not a plural family: the line never states a count, so ".one"/".other" suffixes would only
    // trip PluralRules' per-locale key validation.
    static final String KEY_LINE = "chat.dungeontrain.advancement_revealed";
    static final String KEY_MORE = "chat.dungeontrain.advancement_revealed_more";

    private AdvancementReveal() {}

    /**
     * The revealed advancements among {@code newlyVisible}, sorted by title: not yet earned (an earned
     * hidden advancement turning visible is not a reveal) and with a title (recipe advancements have none).
     */
    public static <T> List<T> select(Collection<T> newlyVisible, Predicate<T> isDone,
                                     Function<T, Optional<String>> title) {
        List<T> revealed = new ArrayList<>();
        for (T node : newlyVisible) {
            if (!isDone.test(node) && title.apply(node).isPresent()) revealed.add(node);
        }
        revealed.sort(Comparator.comparing((T node) -> title.apply(node).orElse("")));
        return revealed;
    }

    /** "Revealed: [A], [B]" (past {@link #MAX_NAMED}, "… +N more"); empty when nothing was revealed. */
    public static Optional<Component> message(List<Component> names) {
        if (names.isEmpty()) return Optional.empty();
        return Optional.of(Component.translatable(KEY_LINE, joined(names)));
    }

    private static Component joined(List<Component> names) {
        MutableComponent list = Component.empty();
        int shown = Math.min(names.size(), MAX_NAMED);
        for (int i = 0; i < shown; i++) {
            if (i > 0) list.append(", ");
            list.append(names.get(i));
        }
        if (names.size() > shown) {
            list.append(" ").append(Component.translatable(KEY_MORE, names.size() - shown));
        }
        return list;
    }
}
