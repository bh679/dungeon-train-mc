package games.brennan.dungeontrain.client.localization.edit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.Map;

/**
 * Draws a recorded screen ({@link ButtonScreenLayouts.Layout}) for the button preview's full context:
 * every widget where it was, the edited button labelled with the typed text and outlined.
 *
 * <p>Draws in the layout's own GUI coordinates — the caller has already translated and scaled into
 * its frame. Buttons are real vanilla buttons, rendered by hand and never added as widgets, so they
 * look right and cannot be clicked; other widgets (edit boxes, lists, sliders) are drawn as their
 * outline and label, enough to show what the button sits beside.</p>
 */
final class ButtonLayoutRenderer {

    private static final int EDITED_OUTLINE = 0xFFFFFF55;
    private static final int OTHER_OUTLINE = 0x80FFFFFF;
    private static final int OTHER_FILL = 0x40000000;
    private static final int OTHER_TEXT = 0xFFA0A0A0;

    private ButtonLayoutRenderer() {}

    /**
     * @param editedKey the key being translated
     * @param label     what the edited button says — the typed text, placeholders filled
     * @param lang      this locale's value for each lang key, so the other buttons read in it too
     */
    static void render(GuiGraphics g, Font font, ButtonScreenLayouts.Layout layout, String editedKey,
                       String label, Map<String, String> lang) {
        for (ButtonScreenLayouts.Widget w : layout.widgets()) {
            boolean edited = editedKey.equals(w.key());
            String text = labelOf(w, edited, label, lang);
            if (w.button()) {
                Button sample = Button.builder(Component.literal(text), b -> { })
                    .bounds(w.x(), w.y(), w.w(), w.h()).build();
                // Off-screen mouse: never hovered, so it shows the resting look players see.
                sample.render(g, -1, -1, 0);
            } else {
                g.fill(w.x(), w.y(), w.x() + w.w(), w.y() + w.h(), OTHER_FILL);
                g.renderOutline(w.x(), w.y(), w.w(), w.h(), OTHER_OUTLINE);
                g.drawString(font, font.plainSubstrByWidth(text, Math.max(0, w.w() - 8)),
                    w.x() + 4, w.y() + (w.h() - font.lineHeight) / 2 + 1, OTHER_TEXT);
            }
            if (edited) {
                g.renderOutline(w.x() - 1, w.y() - 1, w.w() + 2, w.h() + 2, EDITED_OUTLINE);
            }
        }
    }

    /** An option button keeps its value ("Caption: On"); the caption is what gets translated. */
    private static String labelOf(ButtonScreenLayouts.Widget w, boolean edited, String label,
                                  Map<String, String> lang) {
        String caption = edited ? label
            : w.key() != null && lang.containsKey(w.key()) ? lang.get(w.key()) : null;
        if (caption == null) {
            return w.text();
        }
        return w.suffix() == null || w.suffix().isEmpty() ? caption : caption + ": " + w.suffix();
    }
}
