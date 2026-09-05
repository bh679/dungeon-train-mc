package games.brennan.dungeontrain.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The scrolling, carded text canvas behind DT's long-form information pages — the AI Policy page,
 * the Credits page and the Video Tools pages.
 *
 * <p>They are all the same thing underneath: a column of headings, wrapped prose and rows, laid
 * out once into flat draw lists at canvas-relative Y, then drawn inside a scissor-clipped viewport
 * offset by a scroll position, with inline links hit-tested against the same lines. This class owns
 * all of that, plus the shared palette and spacing scale, so the pages cannot drift apart —
 * which they did once already, the Credits page being the copy the AI Policy page was made from.</p>
 *
 * <h2>Using it</h2>
 * <ol>
 *   <li>{@link #beginLayout} with the content column, then call the {@code add*} methods, each of
 *       which takes a canvas-relative Y and returns the Y just below what it added.</li>
 *   <li>A card's height is not known until its contents are laid out, so lay the contents first and
 *       call {@link #addCard} afterwards with the height it turned out to need.</li>
 *   <li>{@link #finishLayout} with the total content height and the viewport bounds.</li>
 *   <li>{@link #renderPanel} from the screen's {@code renderBackground}, and {@link #render} from
 *       its {@code render}.</li>
 * </ol>
 *
 * <p>Accent colours are deliberately NOT here: which colour a section is accented in is a content
 * decision belonging to the page, whereas everything in {@link #COLOUR_CARD_FILL} and friends is
 * chrome that must match everywhere.</p>
 */
public final class CardCanvas {

    // --- Palette. Card fill/border match ContentChoiceCard so these pages read as the same kind
    // --- of surface as the rest of DT's cards.
    public static final int COLOUR_PANEL       = 0xC0101010;
    public static final int COLOUR_HEADER      = 0xFFFFFFFF;
    public static final int COLOUR_DESC        = 0xFFCACACA;
    /** Blue used for inline links (RGB, no alpha — it goes through {@code Style.withColor}). */
    public static final int COLOUR_LINK        = 0x5B9BFF;
    public static final int COLOUR_CARD_FILL   = 0x40000000;
    public static final int COLOUR_CARD_BORDER = 0xFF3A3A4A;
    /** A hairline between rows inside one card — a nested card would read as noise. */
    public static final int COLOUR_DIVIDER     = 0xFF2E2E3A;
    private static final int COLOUR_TRACK      = 0x40000000;
    private static final int COLOUR_THUMB      = 0xFF8A8AA8;

    // --- Spacing scale. Every gap on these pages is one of these; none are ad-hoc.
    /** Inside a card, between its border and its contents. */
    public static final int CARD_PAD     = 8;
    /** Between one card and the next. */
    public static final int CARD_GAP     = 10;
    /** Between stacked paragraphs of the same block. */
    public static final int PARA_GAP     = 6;
    /** Between the framing prose and the run of cards. */
    public static final int SECTION_GAP  = 14;
    /** Between a card heading and its accent bar. */
    public static final int RULE_GAP     = 3;
    /** Between the accent bar and the first row under it. */
    public static final int RULE_TO_BODY = 7;
    /** Between two rows in a list. */
    public static final int ROW_GAP      = 5;
    /** Between a row's icon or photo and its text. */
    public static final int ICON_GAP     = 6;
    /** Padding around the translucent backdrop panel. */
    public static final int PANEL_PAD    = 10;

    /** Vanilla item-icon side length. */
    public static final int ICON     = 16;
    /** The accent bar under a card heading. */
    public static final int RULE_W   = 36;
    public static final int RULE_H   = 2;

    private static final int SCROLL_STEP = 12;
    private static final int SCROLLBAR_W = 4;
    /** Shortest the scroll thumb is allowed to get on a long page. */
    private static final int THUMB_MIN   = 20;

    /**
     * One laid-out text line at a canvas-relative Y. {@code centered} lines are horizontally centred
     * on the screen (title/subtitle); the rest draw at {@code x}. The {@link FormattedCharSequence}
     * carries any inline-link {@link Style}, so both drawing and hit-testing use it directly.
     */
    private record Line(FormattedCharSequence text, int canvasY, boolean centered, int x, int colour) {}

    /** One section panel, spanning the content column at a canvas-relative Y. */
    private record Card(int canvasY, int height) {}

    /** A filled bar — a card heading's accent, or a hairline divider between rows. */
    private record Rule(int canvasY, int x, int w, int h, int colour) {}

    /** One item glyph at a canvas-relative Y. */
    private record Icon(ItemStack stack, int x, int canvasY) {}

    /** One texture (a team photo), drawn scaled from its {@code src}-square source. */
    private record Img(ResourceLocation tex, int x, int canvasY, int w, int h, int src) {}

    private final Font font;

    private final List<Card> cards = new ArrayList<>();
    private final List<Rule> rules = new ArrayList<>();
    private final List<Img> imgs = new ArrayList<>();
    private final List<Icon> icons = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();

    private int colX;
    private int colW;
    private int viewportTop;
    private int viewportBottom;
    private int contentHeight;
    private int scrollY;
    private int maxScroll;

    public CardCanvas(Font font) {
        this.font = font;
    }

    /** Clear the draw lists and set the content column. Call at the top of the screen's init(). */
    public void beginLayout(int colX, int colW) {
        cards.clear();
        rules.clear();
        imgs.clear();
        icons.clear();
        lines.clear();
        this.colX = colX;
        this.colW = colW;
    }

    /**
     * Record the content height and viewport, clamping the scroll position. The scroll position
     * deliberately survives a re-layout, so a window resize does not throw the reader back to the
     * top of the page.
     */
    public void finishLayout(int contentHeight, int viewportTop, int viewportBottom) {
        this.contentHeight = contentHeight;
        this.viewportTop = viewportTop;
        this.viewportBottom = Math.max(viewportTop, viewportBottom);
        this.maxScroll = Math.max(0, contentHeight - (this.viewportBottom - this.viewportTop));
        this.scrollY = Mth.clamp(this.scrollY, 0, this.maxScroll);
    }

    public int colX() {
        return colX;
    }

    public int colW() {
        return colW;
    }

    public int lineHeight() {
        return font.lineHeight;
    }

    public int viewportTop() {
        return viewportTop;
    }

    public int viewportBottom() {
        return viewportBottom;
    }

    /**
     * Screen Y for a canvas Y, at the current scroll position. Public so a page can draw content
     * this class has no business owning — the Video Tools pages' per-frame sprite-sheet clips —
     * in its own scissored pass, scrolling in lockstep with the text laid out here.
     */
    public int screenY(int canvasY) {
        return viewportTop + canvasY - scrollY;
    }

    // ---- Layout ----

    /** A single line centred on the screen. */
    public int addCentered(Component text, int y, int colour) {
        lines.add(new Line(text.getVisualOrderText(), y, true, 0, colour));
        return y + font.lineHeight;
    }

    /** Text wrapped to the content column and centred on the screen. */
    public int addCenteredWrapped(Component text, int y, int colour) {
        for (FormattedCharSequence line : font.split(text, colW)) {
            lines.add(new Line(line, y, true, 0, colour));
            y += font.lineHeight;
        }
        return y;
    }

    /** A single line at the left edge of the content column. */
    public int addLeft(Component text, int y, int colour) {
        return addLineAt(text.getVisualOrderText(), colX, y, colour);
    }

    /** Text wrapped to, and left-aligned with, the content column. */
    public int addLeftWrapped(Component text, int y, int colour) {
        return addWrappedAt(text, colX, colW, y, colour);
    }

    /** A single left-aligned line drawn at {@code x}. */
    public int addLineAt(FormattedCharSequence text, int x, int y, int colour) {
        lines.add(new Line(text, y, false, x, colour));
        return y + font.lineHeight;
    }

    /** Text wrapped to {@code wrapW} and left-aligned at {@code x}. */
    public int addWrappedAt(Component text, int x, int wrapW, int y, int colour) {
        for (FormattedCharSequence line : font.split(text, Math.max(1, wrapW))) {
            lines.add(new Line(line, y, false, x, colour));
            y += font.lineHeight;
        }
        return y;
    }

    /** A card heading's accent bar. Returns the Y just below it. */
    public int addRule(int x, int y, int w, int colour) {
        rules.add(new Rule(y, x, w, RULE_H, colour));
        return y + RULE_H;
    }

    /** A hairline between two rows of one card. Returns the Y just below it. */
    public int addDivider(int x, int y, int w) {
        rules.add(new Rule(y, x, w, 1, COLOUR_DIVIDER));
        return y + 1;
    }

    public void addIcon(ItemStack stack, int x, int y) {
        icons.add(new Icon(stack, x, y));
    }

    /** A texture drawn scaled from a {@code src}-square source (DT's 128² team photos). */
    public void addImg(ResourceLocation tex, int x, int y, int w, int h, int src) {
        imgs.add(new Img(tex, x, y, w, h, src));
    }

    /**
     * The panel behind a section. Call AFTER laying the section's contents, with the height they
     * turned out to need — the card spans the full content column.
     */
    public void addCard(int top, int height) {
        cards.add(new Card(top, height));
    }

    // ---- Input ----

    /**
     * Send the next layout back to the top. For a page that swaps what it is showing — the Video
     * Tools tabs — where an offset carried over from the previous section means nothing. NOT for a
     * re-layout: {@link #finishLayout} keeps the scroll on purpose, so a window resize does not
     * throw the reader back to the top of what they were reading.
     */
    public void resetScroll() {
        scrollY = 0;
    }

    /** Scroll by a wheel delta. Returns whether the page consumed it. */
    public boolean scroll(double delta) {
        if (maxScroll <= 0) {
            return false;
        }
        scrollY = Mth.clamp(scrollY - (int) (delta * SCROLL_STEP), 0, maxScroll);
        return true;
    }

    /** The clickable {@link Style} under the given mouse position within the viewport, or null. */
    public Style styleAt(double mouseX, double mouseY, int screenWidth) {
        if (mouseY < viewportTop || mouseY >= viewportBottom) {
            return null;
        }
        int lh = font.lineHeight;
        double canvasY = mouseY - viewportTop + scrollY;
        for (Line line : lines) {
            if (canvasY < line.canvasY() || canvasY >= line.canvasY() + lh) {
                continue;
            }
            int lineWidth = font.width(line.text());
            int startX = line.centered() ? screenWidth / 2 - lineWidth / 2 : line.x();
            if (mouseX < startX || mouseX >= startX + lineWidth) {
                continue;
            }
            return font.getSplitter().componentStyleAtWidth(line.text(), (int) (mouseX - startX));
        }
        return null;
    }

    // ---- Render ----

    /** The translucent backdrop behind the viewport. Call from the screen's renderBackground. */
    public void renderPanel(GuiGraphics g) {
        g.fill(colX - PANEL_PAD, viewportTop - PANEL_PAD,
                colX + colW + PANEL_PAD, viewportBottom + PANEL_PAD, COLOUR_PANEL);
    }

    /**
     * The clipped content pass, then the scrollbar. Draw order is fixed — cards, then rules, images
     * and icons, then text — so a panel always sits behind its own contents.
     */
    public void render(GuiGraphics g, int screenWidth) {
        int lh = font.lineHeight;
        g.enableScissor(colX - PANEL_PAD, viewportTop, colX + colW + PANEL_PAD, viewportBottom);

        for (Card card : cards) {
            int drawY = screenY(card.canvasY());
            if (offscreen(drawY, card.height())) {
                continue;
            }
            g.fill(colX, drawY, colX + colW, drawY + card.height(), COLOUR_CARD_FILL);
            g.renderOutline(colX, drawY, colW, card.height(), COLOUR_CARD_BORDER);
        }

        for (Rule rule : rules) {
            int drawY = screenY(rule.canvasY());
            if (offscreen(drawY, rule.h())) {
                continue;
            }
            g.fill(rule.x(), drawY, rule.x() + rule.w(), drawY + rule.h(), rule.colour());
        }

        for (Img img : imgs) {
            int drawY = screenY(img.canvasY());
            if (offscreen(drawY, img.h())) {
                continue;
            }
            g.blit(img.tex(), img.x(), drawY, img.w(), img.h(),
                    0.0F, 0.0F, img.src(), img.src(), img.src(), img.src());
        }

        for (Icon icon : icons) {
            int drawY = screenY(icon.canvasY());
            if (offscreen(drawY, ICON)) {
                continue;
            }
            g.renderItem(icon.stack(), icon.x(), drawY);
        }

        for (Line line : lines) {
            int drawY = screenY(line.canvasY());
            if (offscreen(drawY, lh)) {
                continue;
            }
            int x = line.centered() ? screenWidth / 2 - font.width(line.text()) / 2 : line.x();
            g.drawString(font, line.text(), x, drawY, line.colour(), false);
        }

        g.disableScissor();
        renderScrollbar(g);
    }

    /** Whether something of the given height at {@code drawY} falls entirely outside the viewport. */
    private boolean offscreen(int drawY, int height) {
        return drawY + height < viewportTop || drawY > viewportBottom;
    }

    /**
     * A track and thumb at the panel's right edge, drawn only when there is somewhere to scroll —
     * these pages are long enough that without one there is no sign anything is below the fold.
     */
    private void renderScrollbar(GuiGraphics g) {
        if (maxScroll <= 0) {
            return;
        }
        int viewportH = viewportBottom - viewportTop;
        int x = colX + colW + PANEL_PAD - SCROLLBAR_W;
        g.fill(x, viewportTop, x + SCROLLBAR_W, viewportBottom, COLOUR_TRACK);

        int thumbH = Math.min(viewportH,
                Math.max(THUMB_MIN, viewportH * viewportH / Math.max(1, contentHeight)));
        int thumbY = viewportTop + (viewportH - thumbH) * scrollY / maxScroll;
        g.fill(x, thumbY, x + SCROLLBAR_W, thumbY + thumbH, COLOUR_THUMB);
    }
}
