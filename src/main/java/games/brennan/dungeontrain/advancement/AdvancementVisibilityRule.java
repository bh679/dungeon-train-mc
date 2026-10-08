package games.brennan.dungeontrain.advancement;

import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * When a Dungeon Train advancement is shown on the advancements screen, per its visibility mode.
 *
 * <ul>
 *   <li>{@link Mode#PARENT} — <b>hidden until parent</b>: shown once it or its direct parent is earned.
 *       The default for every advancement that has a parent: the frontier, one ring past what's earned.</li>
 *   <li>{@link Mode#ALWAYS} — <b>always visible</b> while its parent is visible: a whole branch shows as
 *       soon as its head does.</li>
 *   <li>{@link Mode#EARNED} — <b>hidden until earned</b>.</li>
 * </ul>
 *
 * <p>A tab's first advancement has no parent: {@code ALWAYS} shows it (and so the tab) from the start,
 * {@code EARNED} only once earned. With no mode set it keeps vanilla's answer, so a hidden tab head stays
 * locked until earned, as the tab copies rely on. Modes are set per advancement in the advancement editor
 * and stored in {@code advancement_tabs.json} ({@code visibility}); read through {@link TabGateways}.</p>
 *
 * <p>Pure over a generic node type so it can be tested without a game; the screen-side caller is
 * {@code AdvancementVisibilityEvaluatorMixin}.</p>
 */
public final class AdvancementVisibilityRule {

    public enum Mode {
        PARENT, ALWAYS, EARNED;

        /** {@code "parent"} / {@code "always"} / {@code "earned"}, or {@code null} for anything else. */
        public static Mode parse(String value) {
            if (value == null) return null;
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private AdvancementVisibilityRule() {}

    /**
     * @param node        the advancement
     * @param parentOf    its parent, or {@code null} for a tab's first advancement
     * @param done        whether an advancement is earned
     * @param modeOf      the mode set for an advancement, or {@code null} for none
     * @param rootDefault vanilla's answer for a tab head with no mode set
     */
    public static <N> boolean isVisible(N node, Function<N, N> parentOf, Predicate<N> done,
                                        Function<N, Mode> modeOf, Predicate<N> rootDefault) {
        if (done.test(node)) return true;
        Mode mode = modeOf.apply(node);
        N parent = parentOf.apply(node);
        if (parent == null) {
            if (mode == null) return rootDefault.test(node);
            return mode == Mode.ALWAYS;
        }
        if (mode == null) mode = Mode.PARENT;
        return switch (mode) {
            case EARNED -> false;
            case PARENT -> done.test(parent);
            case ALWAYS -> isVisible(parent, parentOf, done, modeOf, rootDefault);
        };
    }
}
