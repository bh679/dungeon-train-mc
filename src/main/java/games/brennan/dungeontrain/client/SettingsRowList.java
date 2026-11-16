package games.brennan.dungeontrain.client;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The settings list behind every tab of {@link DungeonTrainClientOptionsScreen}: one setting per line,
 * read left to right as <b>icon · name · one-line hint · control</b>.
 *
 * <p>It replaces vanilla's {@code OptionsList}, whose rows are 150px buttons that have to carry the
 * setting's whole meaning in their own label ("Book Author Chat: OFF") — two across, ten to a tab, and
 * nothing to tell a toggle from a page you open. Here the name and a short grey hint are plain text on
 * the left, so the control on the right can show only its value ({@code ON}, {@code Adult},
 * {@code 80%}, {@code Open}) and the eye can run down one column of values. The long explanation that
 * used to be the button's tooltip is now the whole row's tooltip.</p>
 *
 * <p>The control is still the vanilla widget it always was — a {@code CycleButton} built with
 * {@code displayOnlyValue()}, an {@code OptionInstance} slider with a value-only label, a
 * {@code Button} — and it is the entry's only child, so clicks, keyboard focus, Ctrl+Tab and narration
 * all go through vanilla unchanged. Rows that are not a setting (a caption, a tip line, the account's
 * "checking…" state) go in as a {@link #addWidget full-width widget}, exactly as before.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class SettingsRowList extends ContainerObjectSelectionList<SettingsRowList.Entry> {

    /** A full content row — vanilla's own options-list width. */
    public static final int ROW_W = 310;
    /** One line: two 9px text lines with a little air, and room for a 20px control. */
    public static final int ROW_H = 24;
    /** The value-only control at the right end of a setting row. */
    public static final int CONTROL_W = 100;
    public static final int CONTROL_H = 20;

    private static final int ICON = 16;
    private static final int ICON_GAP = 6;
    /** Air between the hint's end and the control. */
    private static final int TEXT_GAP = 6;
    private static final int NAME_COLOR = 0xFFFFFFFF;
    /** Same grey {@code PerformanceTipRow} dims a tip that does not apply. */
    private static final int HINT_COLOR = 0xFF9A9A9A;
    private static final String ELLIPSIS = "…";
    /** A row a chat link pointed at: solid for {@link #HIGHLIGHT_HOLD_MS}, then fading out by {@link #HIGHLIGHT_MS}. */
    static final long HIGHLIGHT_HOLD_MS = 1000;
    static final long HIGHLIGHT_MS = 3000;
    private static final int HIGHLIGHT_RGB = 0xFFAA00;
    private static final int HIGHLIGHT_FILL_ALPHA = 0x38;
    /** How far the highlight reaches past the row's content, so it does not touch the icon or control. */
    private static final int HIGHLIGHT_PAD = 2;

    private final Font font;
    @Nullable private Entry highlighted;
    private long highlightStartMs;

    public SettingsRowList(Minecraft minecraft, int width, int height, int y) {
        super(minecraft, width, height, y, ROW_H);
        this.font = minecraft.font;
    }

    /** A setting: its icon, name, hint and value-only control, with the long explanation as the row's tooltip. */
    public Entry addSetting(Icon icon, Component name, Component hint, AbstractWidget control,
                            @Nullable Component tooltip) {
        Entry entry = new SettingEntry(icon, name, hint, control, tooltip);
        addEntry(entry);
        return entry;
    }

    /** A row that is not a setting — a caption or a tip line — drawn at the full row width. */
    public void addWidget(AbstractWidget widget) {
        addEntry(new WidgetEntry(widget));
    }

    /** Drop every row — for a list refilled in place, like a search result. */
    public void clear() {
        clearEntries();
    }

    /** How many rows are in the list — the Stream tab sizes itself by it to leave room for the preview. */
    public int rowCount() {
        return children().size();
    }

    /** Commit any slider still holding an unapplied drag — what {@code OptionsList} does when its screen closes. */
    public void applyUnsavedChanges() {
        for (Entry entry : children()) {
            if (entry instanceof SettingEntry setting
                    && setting.control instanceof OptionInstance.OptionInstanceSliderButton<?> slider) {
                slider.applyUnsavedValue();
            }
        }
    }

    /**
     * Centre {@code entry} and highlight it, timed from {@code startMs} so a rebuilt list carries on
     * the fade rather than starting it again. The list must already have its final size.
     */
    public void focus(Entry entry, long startMs) {
        centerScrollOn(entry);
        this.highlighted = entry;
        this.highlightStartMs = startMs;
    }

    /** The highlight's strength {@code elapsedMs} after it started: 1 while held, falling to 0 at the end. */
    static float highlightStrength(long elapsedMs) {
        if (elapsedMs < 0 || elapsedMs >= HIGHLIGHT_MS) return 0f;
        if (elapsedMs <= HIGHLIGHT_HOLD_MS) return 1f;
        return (HIGHLIGHT_MS - elapsedMs) / (float) (HIGHLIGHT_MS - HIGHLIGHT_HOLD_MS);
    }

    @Override
    protected void renderItem(GuiGraphics g, int mouseX, int mouseY, float partialTick, int index,
                              int left, int top, int width, int height) {
        if (getEntry(index) == this.highlighted) {
            drawHighlight(g, left, top, width, height);
        }
        super.renderItem(g, mouseX, mouseY, partialTick, index, left, top, width, height);
    }

    private void drawHighlight(GuiGraphics g, int left, int top, int width, int height) {
        float strength = highlightStrength(Util.getMillis() - this.highlightStartMs);
        if (strength <= 0f) return;
        int x = left - HIGHLIGHT_PAD;
        int y = top - HIGHLIGHT_PAD;
        int w = width + 2 * HIGHLIGHT_PAD;
        int h = height + 2 * HIGHLIGHT_PAD;
        g.fill(x, y, x + w, y + h, (Math.round(HIGHLIGHT_FILL_ALPHA * strength) << 24) | HIGHLIGHT_RGB);
        g.renderOutline(x, y, w, h, (Math.round(0xFF * strength) << 24) | HIGHLIGHT_RGB);
    }

    @Override
    public int getRowWidth() {
        return ROW_W;
    }

    @Override
    protected int getScrollbarPosition() {
        // Where OptionsList puts it: just outside the 310px row, not at the window's edge.
        return super.getScrollbarPosition() + 32;
    }

    // ------------------------------------------------------------------
    // Icons
    // ------------------------------------------------------------------

    /** What sits at the left end of a setting row: an item drawn by the item renderer, or a GUI sprite. */
    public sealed interface Icon {
        void draw(GuiGraphics g, int x, int y);

        static Icon item(ItemLike item) {
            return new ItemIcon(new ItemStack(item));
        }

        /** A sprite of {@code size} px a side, centred in the 16px icon box. */
        static Icon sprite(ResourceLocation sprite, int size) {
            return new SpriteIcon(sprite, size);
        }
    }

    private record ItemIcon(ItemStack stack) implements Icon {
        @Override
        public void draw(GuiGraphics g, int x, int y) {
            g.renderItem(stack, x, y);
        }
    }

    private record SpriteIcon(ResourceLocation sprite, int size) implements Icon {
        @Override
        public void draw(GuiGraphics g, int x, int y) {
            int inset = (ICON - size) / 2;
            g.blitSprite(sprite, x + inset, y + inset, size, size);
        }
    }

    // ------------------------------------------------------------------
    // Entries
    // ------------------------------------------------------------------

    public abstract static class Entry extends ContainerObjectSelectionList.Entry<Entry> {
    }

    private final class SettingEntry extends Entry {

        private final Icon icon;
        private final Component name;
        private final Component hint;
        private final AbstractWidget control;
        @Nullable private final Tooltip tooltip;

        SettingEntry(Icon icon, Component name, Component hint, AbstractWidget control,
                     @Nullable Component tooltip) {
            this.icon = icon;
            this.name = name;
            this.hint = hint;
            this.control = control;
            // No explanation of its own → the hint is the tooltip, so a hint cut with "…" can still be read whole.
            Component shown = tooltip != null ? tooltip : hint;
            this.tooltip = shown.getString().isBlank() ? null : Tooltip.create(shown);
            this.control.setWidth(CONTROL_W);
            this.control.setHeight(CONTROL_H);
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {
            control.setX(left + width - CONTROL_W);
            control.setY(top + (height - CONTROL_H) / 2);

            icon.draw(g, left, top + (height - ICON) / 2);

            int textX = left + ICON + ICON_GAP;
            int textW = control.getX() - TEXT_GAP - textX;
            // Two 9px lines inside the 24px row: name at the top, hint under it.
            int nameY = top + (height - 2 * font.lineHeight - 1) / 2;
            g.drawString(font, oneLine(name, textW), textX, nameY, NAME_COLOR, true);
            g.drawString(font, oneLine(hint, textW), textX, nameY + font.lineHeight + 1, HINT_COLOR, false);

            control.render(g, mouseX, mouseY, partialTick);

            // The explanation that used to be the button's tooltip: shown for the whole row, so
            // hovering the name or the hint explains the setting too, not just hovering the control.
            if (tooltip != null && hovered && mouseX < control.getX()) {
                Screen screen = Minecraft.getInstance().screen;
                if (screen != null) {
                    screen.setTooltipForNextRenderPass(tooltip.toCharSequence(Minecraft.getInstance()));
                }
            }
        }

        /**
         * {@code text} on one line, cut with an ellipsis when it would not fit — a hint that outgrows
         * its room in some locale reads as cut, and the tooltip (the hint itself, for a row with no
         * explanation of its own) carries the whole of it.
         */
        private FormattedCharSequence oneLine(Component text, int width) {
            if (font.width(text) <= width) {
                return text.getVisualOrderText();
            }
            String plain = text.getString();
            String cut = font.plainSubstrByWidth(plain, width - font.width(ELLIPSIS)).stripTrailing();
            return Component.literal(cut + ELLIPSIS).withStyle(text.getStyle()).getVisualOrderText();
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(control);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(control);
        }
    }

    private static final class WidgetEntry extends Entry {

        private final AbstractWidget widget;

        WidgetEntry(AbstractWidget widget) {
            this.widget = widget;
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {
            widget.setX(left);
            widget.setY(top + (height - widget.getHeight()) / 2);
            widget.setWidth(width);
            widget.render(g, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(widget);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(widget);
        }
    }
}
