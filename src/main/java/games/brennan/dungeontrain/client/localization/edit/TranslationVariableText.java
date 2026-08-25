package games.brennan.dungeontrain.client.localization.edit;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.List;

/**
 * Turns an English source string into the component the edit screen draws: plain text, with every
 * format placeholder underlined and carrying a tooltip of what the game puts there.
 *
 * <p>Built as a styled {@link Component} rather than painted by hand because that is what makes the
 * hover work for free — {@code font.split} keeps the {@link Style} on each character, so the screen
 * can ask {@code componentStyleAtWidth} what is under the mouse, exactly as the Support and Credits
 * pages do for their inline links.</p>
 */
public final class TranslationVariableText {

    /** The underline colour. Warm, and unlike any status colour already on this screen. */
    private static final int VARIABLE_COLOUR = 0xFFD65B;

    private TranslationVariableText() {}

    /**
     * The source, with its placeholders underlined and hoverable.
     *
     * <p>A string with no placeholders comes back as one plain literal — no styling, no hover, and
     * the same rendering it had before this existed.</p>
     */
    public static Component decorate(String key, String source) {
        if (source == null || source.isEmpty()) {
            return Component.empty();
        }
        List<TranslationVariable> variables = TranslationVariableScanner.scan(key, source);
        if (variables.isEmpty()) {
            return Component.literal(source);
        }
        MutableComponent out = Component.empty();
        int cursor = 0;
        for (TranslationVariable variable : variables) {
            if (variable.start() > cursor) {
                out.append(Component.literal(source.substring(cursor, variable.start())));
            }
            out.append(Component.literal(variable.token()).withStyle(styleFor(variable)));
            cursor = variable.end();
        }
        if (cursor < source.length()) {
            out.append(Component.literal(source.substring(cursor)));
        }
        return out;
    }

    private static Style styleFor(TranslationVariable variable) {
        return Style.EMPTY
            .withColor(VARIABLE_COLOUR)
            .withUnderlined(true)
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, tooltip(variable)));
    }

    /**
     * What the hover says: the token, what it holds, then the examples — or, when nothing is
     * curated for this slot, an honest line saying so rather than a tooltip that looks broken.
     */
    private static Component tooltip(TranslationVariable variable) {
        MutableComponent out = Component.literal(variable.token())
            .withStyle(style -> style.withColor(VARIABLE_COLOUR).withBold(true));
        out.append(Component.literal("\n"));
        out.append(variable.hasLabel()
            ? Component.literal(variable.label()).withStyle(ChatFormatting.WHITE)
            : Component.translatable("gui.dungeontrain.translate.edit.var.unknown")
                .withStyle(ChatFormatting.WHITE));
        if (!variable.hasExamples()) {
            out.append(Component.literal("\n"));
            out.append(Component.translatable("gui.dungeontrain.translate.edit.var.none")
                .withStyle(ChatFormatting.GRAY));
            return out;
        }
        out.append(Component.literal("\n"));
        out.append(Component.translatable("gui.dungeontrain.translate.edit.var.examples")
            .withStyle(ChatFormatting.GRAY));
        for (String example : variable.examples()) {
            out.append(Component.literal("\n  " + example).withStyle(ChatFormatting.YELLOW));
        }
        return out;
    }
}
