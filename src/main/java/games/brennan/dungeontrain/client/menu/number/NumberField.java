package games.brennan.dungeontrain.client.menu.number;

import games.brennan.dungeontrain.client.menu.CommandRunner;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * One editable number: what it is called, where it sits, and how to step or set it.
 *
 * <p>The three actions are the only thing that differs between the places a number lives — a slash
 * command for the menus, a packet for the world-space panels, a config write for a builder chip —
 * so {@link NumberStepper} can treat every one of them alike.</p>
 *
 * @param title    what the typing prompt is headed with — already translated
 * @param value    the current value, {@link #ALL} when an all-capable field is unbounded
 * @param min      lowest value a typed number may take
 * @param max      highest value a typed number may take
 * @param allowAll whether a blank typed value means {@link #ALL} ("no limit")
 * @param inc      step up once
 * @param dec      step down once
 * @param set      set outright; receives {@link #ALL} for a blank value on an all-capable field
 */
public record NumberField(String title, int value, int min, int max, boolean allowAll,
                          Runnable inc, Runnable dec, IntConsumer set) {

    /** The unbounded value every "max" in the editor uses (level, difficulty, fill). */
    public static final int ALL = -1;

    public NumberField {
        if (max < min) max = min;
    }

    /** The value a typing prompt opens with: blank for "all", otherwise the number. */
    public String typedText() {
        return allowAll && value == ALL ? "" : Integer.toString(value);
    }

    /** Whether {@code typed} may be submitted. */
    public boolean accepts(String typed) {
        return parse(typed) != null;
    }

    /**
     * The value {@code typed} stands for, or null when it can't be submitted: blank (unless
     * all-capable), not a number, or out of range.
     */
    public Integer parse(String typed) {
        String text = typed == null ? "" : typed.trim();
        if (text.isEmpty()) return allowAll ? ALL : null;
        int parsed;
        try {
            parsed = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
        if (allowAll && parsed == ALL) return ALL;
        return parsed < min || parsed > max ? null : parsed;
    }

    /**
     * A number edited by a slash command whose last argument takes {@code inc}, {@code dec} or an
     * integer — the shape every editor command tree shares. A blank value on an all-capable field
     * sends {@code -1}, which is how those commands spell "no maximum".
     */
    public static NumberField ofCommand(String title, String prefix, int value, int min, int max,
                                        boolean allowAll) {
        return ofCommand(title, prefix, value, min, max, allowAll, CommandRunner::run);
    }

    /** {@link #ofCommand(String, String, int, int, int, boolean)} with the command sink injected. */
    public static NumberField ofCommand(String title, String prefix, int value, int min, int max,
                                        boolean allowAll, Consumer<String> run) {
        return ofTokens(title, value, min, max, allowAll, token -> prefix + " " + token, run);
    }

    /**
     * A number edited by a command that takes {@code inc}, {@code dec} or an integer somewhere
     * other than the end: {@code commandFor} splices the token in and may return null when the
     * row has no command, in which case the click does nothing.
     */
    public static NumberField ofTokens(String title, int value, int min, int max, boolean allowAll,
                                       java.util.function.Function<String, String> commandFor) {
        return ofTokens(title, value, min, max, allowAll, commandFor, CommandRunner::run);
    }

    /** {@link #ofTokens(String, int, int, int, boolean, java.util.function.Function)} with the sink injected. */
    public static NumberField ofTokens(String title, int value, int min, int max, boolean allowAll,
                                       java.util.function.Function<String, String> commandFor,
                                       Consumer<String> run) {
        Consumer<String> send = token -> {
            String command = commandFor.apply(token);
            if (command != null) run.accept(command);
        };
        return new NumberField(title, value, min, max, allowAll,
            () -> send.accept("inc"),
            () -> send.accept("dec"),
            v -> send.accept(Integer.toString(v)));
    }
}
