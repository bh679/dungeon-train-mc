package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Typing a new value straight into the cell that shows it.
 *
 * <p>The data sheet is the editing surface: a weight, a level bound or a room dimension is changed
 * by clicking the number and typing over it, rather than by hunting for a matching stepper row
 * further down the pane. This holds the half-typed value and where to draw it.</p>
 *
 * <p>Numbers only, plus a leading minus — the only free-typed values here are counts and level
 * bounds, and {@code -1} is how "no maximum" is spelled. That is deliberately narrower than the
 * menu's name field, which takes letters.</p>
 */
public final class InlineEdit {

    /** Long enough for any level bound; short enough that it cannot outgrow its cell. */
    static final int MAX_LENGTH = 6;

    private String prefix = "";
    private String buffer = "";
    private InventoryEditorLayout.Rect rect;

    public boolean active() {
        return rect != null;
    }

    public String buffer() {
        return buffer;
    }

    /**
     * Start typing over {@code rect}, replacing whatever it showed.
     *
     * @param prefix  the command this becomes, with the typed value appended
     * @param initial what the cell showed, pre-filled so a small nudge is a keystroke away; a
     *                non-numeric label (such as "all") starts empty rather than un-typeable
     */
    public void begin(String prefix, String initial, InventoryEditorLayout.Rect rect) {
        this.prefix = prefix == null ? "" : prefix;
        this.buffer = isTypeable(initial) ? initial : "";
        this.rect = rect;
    }

    public void cancel() {
        prefix = "";
        buffer = "";
        rect = null;
    }

    /** The command to run, or null when nothing was typed. Ends the edit either way. */
    public String submit() {
        String command = buffer.isEmpty() || "-".equals(buffer) ? null : prefix + " " + buffer;
        cancel();
        return command;
    }

    public void backspace() {
        if (!buffer.isEmpty()) buffer = buffer.substring(0, buffer.length() - 1);
    }

    /** Accept a digit, or a minus at the very start. Anything else is dropped. */
    public boolean charTyped(char c) {
        if (!active() || buffer.length() >= MAX_LENGTH) return active();
        if (c >= '0' && c <= '9') {
            buffer = buffer + c;
        } else if (c == '-' && buffer.isEmpty()) {
            buffer = "-";
        }
        return true;
    }

    /** Whether {@code text} is something this field could have produced. */
    private static boolean isTypeable(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_LENGTH) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') continue;
            if (c == '-' && i == 0) continue;
            return false;
        }
        return true;
    }

    public void render(GuiGraphics g, Font font) {
        if (!active()) return;
        g.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), MenuRowPainter.TYPING_BG);
        String shown = buffer + "_";
        g.drawString(font, font.plainSubstrByWidth(shown, rect.w()), rect.x() + 1,
            rect.y() + (rect.h() - font.lineHeight) / 2, MenuRowPainter.TEXT_ON_HOVER, false);
    }
}
