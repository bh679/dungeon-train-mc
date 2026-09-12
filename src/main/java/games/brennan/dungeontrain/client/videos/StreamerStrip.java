package games.brennan.dungeontrain.client.videos;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * The Videos page's <b>Twitch streamers</b> strip, between the toolbar and the video list: a label
 * and one chip per streamer — a small Twitch tile, the channel name and how many days they have
 * streamed — wrapping onto a second line when the window is narrow. A chip opens the channel.
 *
 * <p>The strip's height is fixed by the screen before it is built ({@link #linesFor} over the
 * <em>unfiltered</em> streamers) so filtering can thin the chips without the list underneath
 * jumping. Past {@link #MAX_LINES} the rest fold into one inert {@code +N} chip — the strip must
 * never grow into the list.</p>
 *
 * <p>Tooltips are not drawn here: the list is added after this widget and would paint over them.
 * The screen asks {@link #hoveredChip} after {@code super.render} and draws the tooltip itself.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class StreamerStrip extends AbstractWidget {

    static final int CHIP_H = 18;
    static final int MAX_LINES = 2;
    private static final int GAP = 4;
    private static final int PAD = 3;
    private static final int TILE = CHIP_H - 2 * PAD;
    private static final int CHIP_BG = 0x66000000;
    private static final int CHIP_HOVER = 0x33FFFFFF;
    private static final int NAME_COLOUR = 0xFFFFFFFF;
    private static final int SUB_COLOUR = 0xFF9A9A9A;
    private static final int LABEL_COLOUR = 0xFF9A9A9A;
    private static final int STAR_COLOUR = 0xFFF5C542;
    /** Drawn after the name on a ★ streamer, in {@link #STAR_COLOUR}. */
    private static final String STAR = " ★";

    private final Font font;
    private final Consumer<TwitchStreamers.Streamer> onOpen;
    private List<TwitchStreamers.Streamer> rows = List.of();
    private List<Chip> chips = List.of();
    /** Points an overlay (the uploader suggestion panel) owns — no hover, no click there. */
    private BiPredicate<Double, Double> covered = (mx, my) -> false;

    /** One laid-out chip; {@code streamer == null} is the {@code +N} overflow chip. */
    private record Chip(int x, int y, int w, TwitchStreamers.Streamer streamer, int more) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + CHIP_H;
        }
    }

    public StreamerStrip(Font font, int x, int y, int width, int height, Consumer<TwitchStreamers.Streamer> onOpen) {
        super(x, y, width, height, Component.translatable("gui.dungeontrain.videos.streamers.label"));
        this.font = font;
        this.onOpen = onOpen;
    }

    /** Lines the strip needs for these streamers at this width — 0 when there are none. */
    public static int linesFor(Font font, int width, List<TwitchStreamers.Streamer> streamers) {
        if (streamers.isEmpty()) return 0;
        List<Chip> laid = layout(font, 0, 0, width, streamers);
        int last = laid.get(laid.size() - 1).y();
        return last / (CHIP_H + GAP) + 1;
    }

    /** Pixel height of a strip of {@code lines} lines; 0 for none. */
    public static int heightFor(int lines) {
        return lines <= 0 ? 0 : lines * CHIP_H + (lines - 1) * GAP;
    }

    /** Replace the chips (already filtered). */
    public void setRows(List<TwitchStreamers.Streamer> rows) {
        this.rows = rows == null ? List.of() : rows;
        this.chips = layout(font, getX(), getY(), width, this.rows);
    }

    public void setCoveredBy(BiPredicate<Double, Double> covered) {
        this.covered = covered == null ? (mx, my) -> false : covered;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return super.isMouseOver(mouseX, mouseY) && !covered.test(mouseX, mouseY);
    }

    /** The streamer under the cursor, or {@code null} (also for the {@code +N} chip). */
    public TwitchStreamers.Streamer hoveredChip(double mouseX, double mouseY) {
        if (!visible || !isMouseOver(mouseX, mouseY)) return null;
        for (Chip c : chips) {
            if (c.contains(mouseX, mouseY)) return c.streamer();
        }
        return null;
    }

    /**
     * Lay the label and chips out left-to-right, wrapping at {@code width}; the label sits at the
     * head of the first line. Streamers past {@link #MAX_LINES} become a single {@code +N} chip at
     * the end of the last line (it always fits: it is reserved before the last line is filled).
     */
    private static List<Chip> layout(Font font, int left, int top, int width,
                                     List<TwitchStreamers.Streamer> streamers) {
        List<Chip> out = new ArrayList<>();
        if (streamers.isEmpty()) return out;
        int x = left + labelWidth(font) + GAP;
        int y = top;
        int line = 0;
        int right = left + width;
        for (int i = 0; i < streamers.size(); i++) {
            TwitchStreamers.Streamer s = streamers.get(i);
            int w = chipWidth(font, s);
            int remaining = streamers.size() - i;
            boolean lastLine = line == MAX_LINES - 1;
            // On the last line a chip must also leave room for the +N chip that follows it when
            // anything is still to come — so the overflow chip always fits where it lands.
            int need = lastLine && remaining > 1 ? w + GAP + moreWidth(font, remaining - 1) : w;
            if (x + need > right) {
                if (lastLine) {
                    out.add(new Chip(x, y, moreWidth(font, remaining), null, remaining));
                    return out;
                }
                line++;
                x = left;
                y += CHIP_H + GAP;
                lastLine = line == MAX_LINES - 1;
                need = lastLine && remaining > 1 ? w + GAP + moreWidth(font, remaining - 1) : w;
                if (x + need > right) {
                    // Not even a fresh line takes it (very narrow window): fold the rest here.
                    out.add(new Chip(x, y, moreWidth(font, remaining), null, remaining));
                    return out;
                }
            }
            out.add(new Chip(x, y, w, s, 0));
            x += w + GAP;
        }
        return out;
    }

    private static int labelWidth(Font font) {
        return font.width(Component.translatable("gui.dungeontrain.videos.streamers.label"));
    }

    private static int chipWidth(Font font, TwitchStreamers.Streamer s) {
        int star = s.devFav() ? font.width(STAR) : 0;
        return PAD + TILE + PAD + font.width(chipText(s)) + star + PAD;
    }

    private static int moreWidth(Font font, int n) {
        return PAD + TILE + PAD + font.width(moreText(n)) + PAD;
    }

    private static String chipText(TwitchStreamers.Streamer s) {
        return Component.translatable("gui.dungeontrain.videos.streamers.chip", s.name(), s.streamDays()).getString();
    }

    private static String moreText(int n) {
        return Component.translatable("gui.dungeontrain.videos.streamers.more", n).getString();
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (rows.isEmpty()) return;
        int labelY = getY() + (CHIP_H - font.lineHeight) / 2;
        g.drawString(font, Component.translatable("gui.dungeontrain.videos.streamers.label"), getX(), labelY, LABEL_COLOUR);
        for (Chip c : chips) {
            boolean hovered = c.streamer() != null && isMouseOver(mouseX, mouseY) && c.contains(mouseX, mouseY);
            g.fill(c.x(), c.y(), c.x() + c.w(), c.y() + CHIP_H, CHIP_BG);
            if (hovered) g.fill(c.x(), c.y(), c.x() + c.w(), c.y() + CHIP_H, CHIP_HOVER);
            int tileX = c.x() + PAD;
            int tileY = c.y() + PAD;
            int tile = VideoEntry.Platform.TWITCH.tileColour();
            g.fill(tileX, tileY, tileX + TILE, tileY + TILE, tile);
            PlatformToggleButton.drawBubble(g, tileX, tileY, TILE, NAME_COLOUR, tile);
            int textX = tileX + TILE + PAD;
            int textY = c.y() + (CHIP_H - font.lineHeight) / 2 + 1;
            if (c.streamer() == null) {
                g.drawString(font, moreText(c.more()), textX, textY, SUB_COLOUR);
                continue;
            }
            String text = chipText(c.streamer());
            g.drawString(font, text, textX, textY, NAME_COLOUR);
            if (c.streamer().devFav()) {
                g.drawString(font, STAR, textX + font.width(text), textY, STAR_COLOUR);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !active || button != 0) return false;
        TwitchStreamers.Streamer s = hoveredChip(mouseX, mouseY);
        if (s == null) return false;
        playDownSound(Minecraft.getInstance().getSoundManager());
        onOpen.accept(s);
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE,
                Component.translatable("gui.dungeontrain.videos.streamers.narration", rows.size()));
    }
}
