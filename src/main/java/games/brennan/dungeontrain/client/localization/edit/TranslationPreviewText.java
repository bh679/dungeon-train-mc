package games.brennan.dungeontrain.client.localization.edit;

import java.util.List;
import java.util.function.Function;

/**
 * The arithmetic behind the translation previews, kept free of Minecraft so it can be tested.
 *
 * <p>Two questions: what does the line read once the game has filled its {@code %s} in, and how
 * many pixels too wide is a label for the button it sits on.</p>
 */
public final class TranslationPreviewText {

    /**
     * The window Minecraft opens at by default, and the smallest one it is designed for: 854×480 at
     * GUI scale Auto, which picks scale 2 — so every screen has to work in 427×240 GUI pixels.
     */
    public static final int SMALL_SCREEN_WIDTH = 427;
    public static final int SMALL_SCREEN_HEIGHT = 240;
    /** The GUI scale Auto resolves to on that window. */
    public static final int SMALL_SCREEN_GUI_SCALE = 2;

    /**
     * Vanilla's {@code AbstractButton} leaves 2px either side of its label; text wider than what is
     * left scrolls back and forth instead of being drawn whole.
     */
    static final int BUTTON_TEXT_MARGIN = 2;

    /** The chat box at default settings: Chat Width 100% is 320 GUI pixels. */
    public static final int CHAT_WIDTH = 320;

    private TranslationPreviewText() {}

    /**
     * {@code text} with each placeholder replaced by {@code valueFor}, and {@code %%} collapsed to
     * the single percent sign the game prints.
     *
     * @param variables the placeholders in {@code text}, in order, as
     *                  {@link TranslationVariableScanner#scan} found them
     * @param valueFor  what goes in a slot, or null to leave its token showing — better an honest
     *                  {@code %s} than a guess that makes the line look shorter than it will be
     */
    public static String fill(String text, List<TranslationVariable> variables,
                              Function<TranslationVariable, String> valueFor) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        int cursor = 0;
        for (TranslationVariable variable : variables) {
            if (variable.start() < cursor || variable.end() > text.length()) {
                continue;   // not from this text; skip rather than corrupt the line
            }
            out.append(unescape(text.substring(cursor, variable.start())));
            String value = valueFor == null ? null : valueFor.apply(variable);
            out.append(value == null ? variable.token() : value);
            cursor = variable.end();
        }
        out.append(unescape(text.substring(cursor)));
        return out.toString();
    }

    private static String unescape(String plain) {
        return plain.replace("%%", "%");
    }

    /**
     * How many pixels {@code textWidth} overruns a button {@code buttonWidth} wide — zero or less
     * means it fits and is drawn whole.
     */
    public static int buttonOverflow(int textWidth, int buttonWidth) {
        return textWidth - (buttonWidth - BUTTON_TEXT_MARGIN * 2);
    }
}
