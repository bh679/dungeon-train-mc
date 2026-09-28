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
    private String shown = "";
    private InventoryEditorLayout.Rect rect;

    public boolean active() {
        return rect != null;
    }

    public String buffer() {
        return buffer;
    }

    /**
     * Start typing over {@code rect}.
     *
     * <p>The field starts empty, so what is typed replaces the old value rather than running on
     * from it — clicking a weight of 20 and typing 5 means five, not two hundred and five. The old
     * value stays on screen behind the caret until the first keystroke, so it is still there to
     * read while deciding.</p>
     *
     * @param prefix the command this becomes, with the typed value appended
     * @param shown  what the cell was showing, drawn faintly until typing starts
     */
    public void begin(String prefix, String shown, InventoryEditorLayout.Rect rect) {
        this.prefix = prefix == null ? "" : prefix;
        this.buffer = "";
        this.shown = shown == null ? "" : shown;
        this.rect = rect;
    }

    public void cancel() {
        prefix = "";
        buffer = "";
        shown = "";
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

    public void render(GuiGraphics g, Font font) {
        if (!active()) return;
        // Wide enough for what is being typed, so a longer value is not painted over the cell
        // beside it while it is being entered.
        String text = buffer.isEmpty() ? shown : buffer;
        int width = Math.max(rect.w(), font.width(text) + 6);
        g.fill(rect.x(), rect.y(), rect.x() + width, rect.bottom(), MenuRowPainter.TYPING_BG);
        int colour = buffer.isEmpty() ? 0x60000000 : MenuRowPainter.TEXT_ON_HOVER;
        g.drawString(font, text, rect.x() + 1, rect.y() + (rect.h() - font.lineHeight) / 2, colour, false);
        g.drawString(font, "_", rect.x() + 1 + font.width(buffer),
            rect.y() + (rect.h() - font.lineHeight) / 2, MenuRowPainter.TEXT_ON_HOVER, false);
    }
}
