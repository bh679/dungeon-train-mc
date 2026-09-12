package games.brennan.dungeontrain.client.videos;

import games.brennan.dungeontrain.client.ui.ListScrollbar;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * The Videos page's scrolling list: one row per video — thumbnail (or platform tile), title, and a
 * {@code uploader · views · date} line, with a ★ on the operator's picks. Clicking a row opens it.
 * Twitch streamer markers never reach this list — {@link VideoQuery} keeps them for {@link StreamerStrip}.
 *
 * <p>Hand-rolled on {@link ListScrollbar} like every other list in the mod ({@code ShaderPackList},
 * {@code TranslationListWidget}) rather than an {@code ObjectSelectionList}, so the row geometry and
 * scrollbar behaviour match the rest of the menus. Ninety rows is comfortably within "draw the
 * visible ones and skip the rest".</p>
 *
 * <p>Thumbnails are requested only for rows that are on screen — {@link VideoThumbnails} starts a
 * download the first time a row is drawn, so scrolling is what paces the network, not the length
 * of the list.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class VideoList extends AbstractWidget {

    private static final int PAD = 4;
    /** Thumbnail cell — 16:9, the shape every platform's poster frame comes in. */
    static final int THUMB_W = 64;
    static final int THUMB_H = 36;
    private static final int TEXT_GAP = 6;

    private static final int BG = 0x66000000;
    private static final int ROW_HOVER = 0x33FFFFFF;
    private static final int ROW_ALT = 0x18FFFFFF;
    private static final int TITLE_COLOUR = 0xFFFFFFFF;
    private static final int SUB_COLOUR = 0xFF9A9A9A;
    private static final int STAR_COLOUR = 0xFFF5C542;
    /** The ⚑ in the row's bottom-right corner: faint until hovered, filled once this player flagged it. */
    private static final int FLAG_IDLE = 0x60FFFFFF;
    private static final int FLAG_HOVER = 0xFFFFFFFF;
    private static final int FLAG_DONE = 0xFFE08A3A;
    /** Square hit target around the ⚑, so it is clickable without pixel aim. */
    private static final int FLAG_HIT = 12;
    private static final int TILE_TEXT = 0xFFFFFFFF;

    private final Font font;
    private final Consumer<VideoEntry> onOpen;
    private final Consumer<VideoEntry> onFlag;
    private final ListScrollbar scrollbar = new ListScrollbar();

    private List<VideoEntry> rows = List.of();
    private int scroll;
    /**
     * Where something else is drawn over this list — the uploader suggestion panel. A point it
     * claims is neither hovered nor clickable here, so a row cannot light up or open under it.
     */
    private BiPredicate<Double, Double> covered = (mx, my) -> false;

    public VideoList(Font font, int x, int y, int width, int height,
                     Consumer<VideoEntry> onOpen, Consumer<VideoEntry> onFlag) {
        super(x, y, width, height, Component.translatable("gui.dungeontrain.videos.list"));
        this.font = font;
        this.onOpen = onOpen;
        this.onFlag = onFlag;
    }

    /** Replace the rows (already filtered and sorted) and jump back to the top. */
    public void setRows(List<VideoEntry> rows) {
        this.rows = rows == null ? List.of() : rows;
        scroll = 0;
    }

    public int rowCount() {
        return rows.size();
    }

    /** Tell the list which points an overlay owns; see {@link #covered}. */
    public void setCoveredBy(BiPredicate<Double, Double> covered) {
        this.covered = covered == null ? (mx, my) -> false : covered;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return super.isMouseOver(mouseX, mouseY) && !covered.test(mouseX, mouseY);
    }

    private int rowHeight() {
        return THUMB_H + PAD * 2;
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

    private void renderRow(GuiGraphics g, VideoEntry v, int index, int rowY, int rowH, int mouseX, int mouseY) {
        int right = getX() + width - ListScrollbar.WIDTH - 1;
        boolean hovered = isMouseOver(mouseX, mouseY) && mouseY >= rowY && mouseY < rowY + rowH;
        if (hovered) {
            g.fill(getX(), rowY, right, rowY + rowH, ROW_HOVER);
        } else if ((index & 1) == 1) {
            g.fill(getX(), rowY, right, rowY + rowH, ROW_ALT);
        }

        int thumbX = getX() + PAD;
        int thumbY = rowY + PAD;
        renderThumb(g, v, thumbX, thumbY);

        int textX = thumbX + THUMB_W + TEXT_GAP;
        int textRight = right - PAD;
        // The star takes the row's top-right corner; the title yields to it only on starred rows.
        int titleRight = textRight;
        if (v.devFav()) {
            String star = "★";
            g.drawString(font, star, textRight - font.width(star), thumbY, STAR_COLOUR);
            titleRight = textRight - font.width(star) - PAD;
        }
        // Two text lines centred on the thumbnail's height.
        int textY = thumbY + (THUMB_H - font.lineHeight * 2 - 2) / 2;
        g.drawString(font, font.plainSubstrByWidth(v.displayTitle(), titleRight - textX), textX, textY, TITLE_COLOUR);
        // The sub-line yields to the flag in the bottom-right corner.
        int subRight = textRight - FLAG_HIT - PAD;
        g.drawString(font, font.plainSubstrByWidth(subLine(v), subRight - textX), textX,
                textY + font.lineHeight + 2, SUB_COLOUR);

        // ⚑ — report this video. Faint so it does not compete with the row, bright under the cursor,
        // and filled orange once this player has flagged it.
        boolean done = VideoCatalog.isFlagged(v.id());
        boolean overFlag = hovered && isOverFlag(mouseX, mouseY, rowY, rowH);
        int colour = done ? FLAG_DONE : (overFlag ? FLAG_HOVER : FLAG_IDLE);
        String flag = "⚑";
        int fx = textRight - FLAG_HIT + (FLAG_HIT - font.width(flag)) / 2;
        int fy = rowY + rowH - PAD - font.lineHeight;
        g.drawString(font, flag, fx, fy, colour);
    }

    /** Is the cursor on the row's ⚑ hit square (bottom-right corner)? */
    private boolean isOverFlag(double mouseX, double mouseY, int rowY, int rowH) {
        int right = getX() + width - ListScrollbar.WIDTH - 1 - PAD;
        int bottom = rowY + rowH - PAD;
        return mouseX >= right - FLAG_HIT && mouseX < right && mouseY >= bottom - FLAG_HIT && mouseY < bottom;
    }

    /** {@code uploader · 1.2K views · 2026-09-11}, dropping any part the relay had no value for. */
    private static String subLine(VideoEntry v) {
        StringBuilder sb = new StringBuilder();
        if (v.hasChannel()) {
            sb.append(v.channel());
        }
        String views = VideoQuery.compactViews(v.views());
        if (views != null) {
            if (!sb.isEmpty()) sb.append(" · ");
            sb.append(Component.translatable("gui.dungeontrain.videos.views", views).getString());
        }
        if (v.day() != null) {
            if (!sb.isEmpty()) sb.append(" · ");
            sb.append(v.day());
        }
        if (sb.isEmpty()) {
            sb.append(Component.translatable("gui.dungeontrain.videos.platform." + v.platform().key()).getString());
        }
        return sb.toString();
    }

    /** The YouTube thumbnail when decoded, else a platform-coloured tile naming the platform. */
    private void renderThumb(GuiGraphics g, VideoEntry v, int x, int y) {
        VideoThumbnails.Thumb thumb = VideoThumbnails.textureFor(v);
        if (thumb != null) {
            // Letterbox inside the cell — mqdefault is 16:9 so this is normally exact, but a
            // different aspect must not be squashed.
            float imgAspect = thumb.width() / (float) Math.max(1, thumb.height());
            float cellAspect = THUMB_W / (float) THUMB_H;
            int dw;
            int dh;
            if (cellAspect > imgAspect) {
                dh = THUMB_H;
                dw = Math.round(THUMB_H * imgAspect);
            } else {
                dw = THUMB_W;
                dh = Math.round(THUMB_W / imgAspect);
            }
            g.fill(x, y, x + THUMB_W, y + THUMB_H, 0xFF000000);
            g.blit(thumb.texture(), x + (THUMB_W - dw) / 2, y + (THUMB_H - dh) / 2, dw, dh,
                    0.0F, 0.0F, thumb.width(), thumb.height(), thumb.width(), thumb.height());
            return;
        }
        g.fill(x, y, x + THUMB_W, y + THUMB_H, v.platform().tileColour());
        String label = Component.translatable("gui.dungeontrain.videos.platform." + v.platform().key()).getString();
        label = font.plainSubstrByWidth(label, THUMB_W - 4);
        g.drawCenteredString(font, label, x + THUMB_W / 2, y + (THUMB_H - font.lineHeight) / 2, TILE_TEXT);
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
        int rowY = getY() + index * rowHeight() - scroll;
        VideoEntry v = rows.get(index);
        if (isOverFlag(mouseX, mouseY, rowY, rowHeight())) {
            if (!VideoCatalog.isFlagged(v.id())) onFlag.accept(v);
            return true;
        }
        onOpen.accept(v);
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
        scroll = Mth.clamp(scroll - (int) (scrollY * rowHeight()), 0, maxScroll());
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE,
                Component.translatable("gui.dungeontrain.videos.list.narration", rows.size()));
    }
}
