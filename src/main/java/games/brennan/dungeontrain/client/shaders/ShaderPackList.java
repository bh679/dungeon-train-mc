package games.brennan.dungeontrain.client.shaders;

import games.brennan.dungeontrain.client.ui.ListScrollbar;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Shaders page's left column: "Shaders off" plus the supported packs, one selectable row each.
 *
 * <p>Hand-rolled rather than an {@code ObjectSelectionList}, matching every other list in the mod
 * (see {@code TranslationListWidget}) and sharing their {@link ListScrollbar}. Nine rows never need
 * virtualising, but the row geometry is the same shape as the others so the two cannot drift.</p>
 *
 * <p>Each row says what the pack is and what the game would do about it — the installed / active
 * tag is on the row, not only in the detail pane, so the page answers "which of these do I already
 * have" without nine clicks.</p>
 */
public final class ShaderPackList extends AbstractWidget {

    /** {@code null} pack = the "Shaders off" row, which is always first. */
    public record Row(ShaderPack pack) {
        boolean isOff() {
            return pack == null;
        }
    }

    private static final int PAD = 4;
    private static final int ROW_LINES = 2;
    private static final int BG = 0x66000000;
    private static final int ROW_HOVER = 0x33FFFFFF;
    private static final int ROW_SELECTED = 0x44FFFFFF;
    private static final int ROW_ALT = 0x18FFFFFF;
    private static final int NAME_COLOUR = 0xFFFFFFFF;
    private static final int SUB_COLOUR = 0xFF9A9A9A;
    private static final int ACTIVE_COLOUR = 0xFF7FDD7F;
    private static final int INSTALLED_COLOUR = 0xFF5B9BFF;

    private final Font font;
    private final Consumer<Row> onSelect;
    private final ListScrollbar scrollbar = new ListScrollbar();
    private final List<Row> rows = new ArrayList<>();

    private Row selected;
    private int scroll;

    public ShaderPackList(Font font, int x, int y, int width, int height, Consumer<Row> onSelect) {
        super(x, y, width, height, Component.translatable("gui.dungeontrain.shaders.list"));
        this.font = font;
        this.onSelect = onSelect;
        rows.add(new Row(null));
        for (ShaderPack pack : ShaderPack.all()) {
            rows.add(new Row(pack));
        }
    }

    public List<Row> rows() {
        return rows;
    }

    public void select(Row row) {
        this.selected = row;
    }

    public Row selected() {
        return selected;
    }

    private int rowHeight() {
        return font.lineHeight * ROW_LINES + PAD * 2;
    }

    private int totalHeight() {
        return rows.size() * rowHeight();
    }

    private int maxScroll() {
        return Math.max(0, totalHeight() - height);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(getX(), getY(), getX() + width, getY() + height, BG);
        int rowH = rowHeight();
        g.enableScissor(getX(), getY(), getX() + width, getY() + height);
        for (int i = 0; i < rows.size(); i++) {
            int rowY = getY() + i * rowH - scroll;
            if (rowY + rowH < getY() || rowY > getY() + height) {
                continue;
            }
            renderRow(g, rows.get(i), i, rowY, rowH, mouseX, mouseY);
        }
        g.disableScissor();
        scrollbar.render(g, getX(), getY(), width, height, totalHeight(), scroll, maxScroll());
    }

    private void renderRow(GuiGraphics g, Row row, int index, int rowY, int rowH,
                           int mouseX, int mouseY) {
        int right = getX() + width - ListScrollbar.WIDTH - 1;
        boolean hovered = isMouseOver(mouseX, mouseY) && mouseY >= rowY && mouseY < rowY + rowH;
        if (row.equals(selected)) {
            g.fill(getX(), rowY, right, rowY + rowH, ROW_SELECTED);
        } else if (hovered) {
            g.fill(getX(), rowY, right, rowY + rowH, ROW_HOVER);
        } else if ((index & 1) == 1) {
            g.fill(getX(), rowY, right, rowY + rowH, ROW_ALT);
        }

        int textX = getX() + PAD;
        int textY = rowY + PAD;
        int textWidth = right - textX - PAD;
        if (row.isOff()) {
            g.drawString(font, font.plainSubstrByWidth(
                    Component.translatable("gui.dungeontrain.shaders.off").getString(), textWidth),
                    textX, textY, NAME_COLOUR);
            g.drawString(font, font.plainSubstrByWidth(
                    Component.translatable("gui.dungeontrain.shaders.off.sub").getString(), textWidth),
                    textX, textY + font.lineHeight, SUB_COLOUR);
            return;
        }

        ShaderPack pack = row.pack();
        g.drawString(font, font.plainSubstrByWidth(pack.name(), textWidth), textX, textY, NAME_COLOUR);
        g.drawString(font, font.plainSubstrByWidth(pack.version() + " · " + pack.author(), textWidth),
                textX, textY + font.lineHeight, SUB_COLOUR);

        // The tag rides the row's right edge so the column reads as a status list at a glance.
        String tag = null;
        int colour = SUB_COLOUR;
        switch (ShaderPackLibrary.stateOf(pack)) {
            case ACTIVE -> {
                tag = Component.translatable("gui.dungeontrain.shaders.tag.active").getString();
                colour = ACTIVE_COLOUR;
            }
            case INSTALLED -> {
                tag = Component.translatable("gui.dungeontrain.shaders.tag.installed").getString();
                colour = INSTALLED_COLOUR;
            }
            case DOWNLOADING -> {
                tag = Math.round(ShaderPackDownloader.progress(pack) * 100) + "%";
                colour = INSTALLED_COLOUR;
            }
            default -> { }
        }
        if (tag != null) {
            g.drawString(font, tag, right - PAD - font.width(tag), textY, colour);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !active || button != 0 || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        if (maxScroll() > 0 && scrollbar.isOverTrack(mouseX, getX(), width)) {
            scrollbar.begin();
            scroll = scrollbar.scrollFor(mouseY, getY(), height, totalHeight(), maxScroll());
            return true;
        }
        int index = (int) ((mouseY - getY() + scroll) / rowHeight());
        if (index < 0 || index >= rows.size()) {
            return false;
        }
        playDownSound(Minecraft.getInstance().getSoundManager());
        selected = rows.get(index);
        onSelect.accept(selected);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!scrollbar.isDragging()) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        scroll = scrollbar.scrollFor(mouseY, getY(), height, totalHeight(), maxScroll());
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        scrollbar.end();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!visible || !isMouseOver(mouseX, mouseY) || maxScroll() == 0) {
            return false;
        }
        scroll = Mth.clamp(scroll - (int) (scrollY * font.lineHeight * 3), 0, maxScroll());
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE,
                Component.translatable("gui.dungeontrain.shaders.list.narration", rows.size() - 1));
    }
}
