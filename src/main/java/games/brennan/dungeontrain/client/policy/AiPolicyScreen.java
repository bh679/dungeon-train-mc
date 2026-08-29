package games.brennan.dungeontrain.client.policy;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * The <b>AI Policy</b> page — a plain statement of what in Dungeon Train is and is not made by AI,
 * reachable from the Dungeon Train Options screen and from the Credits page.
 *
 * <p>The page frames itself with un-carded prose — an intro at the top and a thank-you at the
 * bottom — and puts everything a player is actually scanning for into cards, one per section, laid
 * out from {@link AiPolicyContent}. Each card is an outlined panel with its heading over a short
 * accent bar, and each bullet carries a 16px item glyph in the margin. The colours do real work:
 * green for the list of things made by hand, amber for the honest disclosure of where AI is used.
 * The changelog bullet ends in a link to the Discord updates channel.</p>
 *
 * <p>Layout runs once in {@link #init} into four flat, canvas-relative draw lists — {@link Card}s,
 * {@link Rule}s, {@link Icon}s and {@link Line}s — which {@link #render} then draws in that order
 * (so a panel stays behind its own contents), inside a scissor-clipped viewport offset by
 * {@link #scrollY}. Inline links are hit-tested in {@link #mouseClicked} against the same lines and
 * opened through vanilla's {@link ConfirmLinkScreen}, returning here. {@code Done} is fixed below
 * the viewport and returns to whichever screen opened this one.</p>
 *
 * <p>A card's height is not known until its rows have been laid out, so {@link #addCard} measures
 * the rows first and records the {@link Card} afterwards with the height it ended up needing.</p>
 */
public final class AiPolicyScreen extends Screen {

    private static final int MAX_COL_W   = 360;
    private static final int SIDE_MARGIN = 40;
    private static final int PANEL_PAD   = 10;
    private static final int TOP         = 16;

    // --- Spacing scale. Every gap on this page is one of these; none are ad-hoc. ---
    /** Inside a card, between its border and its contents. */
    private static final int CARD_PAD    = 8;
    /** Between one card and the next. */
    private static final int CARD_GAP    = 10;
    /** Between stacked paragraphs of the same block. */
    private static final int PARA_GAP    = 6;
    /** Between the framing prose and the run of cards. */
    private static final int SECTION_GAP = 14;
    /** Between a card heading and its accent bar. */
    private static final int RULE_GAP    = 3;
    /** Between the accent bar and the first row under it. */
    private static final int RULE_TO_BODY = 7;
    /** Between two bullet rows. */
    private static final int ROW_GAP     = 5;
    /** Between a bullet's glyph and its text. */
    private static final int ICON_GAP    = 6;

    /** Vanilla item-icon side length. */
    private static final int ICON = 16;
    private static final int RULE_W = 36;
    private static final int RULE_H = 2;

    private static final int SCROLL_STEP = 12;
    private static final int SCROLLBAR_W = 4;

    private static final int COLOUR_PANEL  = 0xC0101010;
    private static final int COLOUR_HEADER = 0xFFFFFFFF;
    private static final int COLOUR_DESC   = 0xFFCACACA;
    /** Blue used for inline links (RGB, no alpha). */
    private static final int COLOUR_LINK   = 0x5B9BFF;

    // Card fill/border, and the scrollbar. Taken from ContentChoiceCard rather than picked afresh,
    // so this page reads as the same kind of surface as the rest of DT's cards.
    private static final int COLOUR_CARD_FILL   = 0x40000000;
    private static final int COLOUR_CARD_BORDER = 0xFF3A3A4A;
    private static final int COLOUR_TRACK       = 0x40000000;
    private static final int COLOUR_THUMB       = 0xFF8A8AA8;

    /** Shortest the scroll thumb is allowed to get on a long page. */
    private static final int THUMB_MIN = 20;

    /**
     * The {@code #minor-updates} Discord message the changelog line points at. A permalink to one
     * specific message rather than a server invite, so it is baked here rather than coming from
     * {@link games.brennan.dungeontrain.client.links.OfficialLinks} with the relay-served bases.
     */
    private static final String UPDATES_URL =
            "https://discord.com/channels/680177367381049356/693919099876671508/1541810418274668724";

    private final Screen parent;

    // Computed in init(), consumed in render()/click handling.
    private int colX;
    private int colW;
    private int viewportTop;
    private int viewportBottom;
    private int contentHeight;
    private int scrollY;
    private int maxScroll;

    private final List<Card> cards = new ArrayList<>();
    private final List<Rule> rules = new ArrayList<>();
    private final List<Icon> icons = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();

    /**
     * One laid-out text line at a canvas-relative Y. {@code centered} lines are horizontally centred
     * on the screen (title/subtitle); the rest draw at {@code x}. The {@link FormattedCharSequence}
     * carries any inline-link {@link Style}, so both drawing and hit-testing use it directly.
     */
    private record Line(FormattedCharSequence text, int canvasY, boolean centered, int x, int colour) {}

    /** One section panel, spanning the content column at a canvas-relative Y. */
    private record Card(int canvasY, int height) {}

    /** The short accent bar under a card heading. */
    private record Rule(int canvasY, int x, int w, int colour) {}

    /** One bullet's item glyph at a canvas-relative Y. */
    private record Icon(ItemStack stack, int x, int canvasY) {}

    public AiPolicyScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.ai_policy.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        cards.clear();
        rules.clear();
        icons.clear();
        lines.clear();

        colW = Math.min(MAX_COL_W, this.width - SIDE_MARGIN);
        colX = (this.width - colW) / 2;
        int lh = this.font.lineHeight;

        int y = 0;

        // Title + subtitle, centred.
        y = addCentered(this.title, y, lh, COLOUR_HEADER);
        y += PARA_GAP;
        y = addCenteredWrapped(Component.translatable("gui.dungeontrain.ai_policy.subtitle"), y, lh, COLOUR_DESC);
        y += SECTION_GAP;

        // Intro — un-carded on purpose: it frames the page rather than being a section of it.
        y = addLeftWrapped(Component.translatable("gui.dungeontrain.ai_policy.intro.1"), y, lh, COLOUR_DESC);
        y += PARA_GAP;
        y = addLeftWrapped(Component.translatable("gui.dungeontrain.ai_policy.intro.2"), y, lh, COLOUR_DESC);
        y += SECTION_GAP;

        List<AiPolicyContent.Section> sections = AiPolicyContent.sections();
        for (int i = 0; i < sections.size(); i++) {
            y = addCard(sections.get(i), y, lh);
            if (i < sections.size() - 1) {
                y += CARD_GAP;
            }
        }
        y += SECTION_GAP;

        y = addLeftWrapped(Component.translatable("gui.dungeontrain.ai_policy.closing"), y, lh, COLOUR_DESC);
        y += PARA_GAP;
        y = addLeftWrapped(Component.translatable("gui.dungeontrain.ai_policy.signature"), y, lh, COLOUR_HEADER);

        contentHeight = y;

        // The viewport ends just above the Done button so scrolling content never overlaps it.
        int rowY = this.height - 28;
        viewportTop = TOP;
        viewportBottom = rowY - 8;
        if (viewportBottom < viewportTop) {
            viewportBottom = viewportTop;
        }
        maxScroll = Math.max(0, contentHeight - (viewportBottom - viewportTop));
        scrollY = Mth.clamp(scrollY, 0, maxScroll);

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(this.width / 2 - 50, rowY, 100, 20)
                .build());
    }

    /**
     * Lay out one section as a card: heading, accent bar, then either a wrapped paragraph or the
     * bullet rows. Returns the canvas Y just below the card's bottom border.
     */
    private int addCard(AiPolicyContent.Section section, int top, int lh) {
        int innerX = colX + CARD_PAD;
        int innerW = Math.max(1, colW - CARD_PAD * 2);
        int y = top + CARD_PAD;

        y = addLineAt(Component.translatable(section.headerKey()).getVisualOrderText(),
                innerX, y, lh, COLOUR_HEADER);
        y += RULE_GAP;
        rules.add(new Rule(y, innerX, Math.min(RULE_W, innerW), section.accent()));
        y += RULE_H + RULE_TO_BODY;

        if (section.isParagraph()) {
            y = addWrappedAt(Component.translatable(section.body()), innerX, innerW, y, lh, COLOUR_DESC);
        } else {
            int textX = innerX + ICON + ICON_GAP;
            int textW = Math.max(1, innerW - ICON - ICON_GAP);
            List<AiPolicyContent.Bullet> bullets = section.bullets();
            for (int i = 0; i < bullets.size(); i++) {
                AiPolicyContent.Bullet bullet = bullets.get(i);
                // Centre the glyph on the row's FIRST line, not on the whole row — a three-line
                // bullet with a vertically-centred icon reads as though the icon belongs to the
                // middle line.
                int iconTop = y - (ICON - lh) / 2;
                icons.add(new Icon(new ItemStack(bullet.glyph()), innerX, iconTop));

                int textBottom = addWrappedAt(bulletText(bullet), textX, textW, y, lh, COLOUR_DESC);
                y = Math.max(textBottom, iconTop + ICON);
                if (i < bullets.size() - 1) {
                    y += ROW_GAP;
                }
            }
        }

        y += CARD_PAD;
        cards.add(new Card(top, y - top));
        return y;
    }

    /**
     * A bullet's text. The changelog line is the one that takes an argument — a link to the Discord
     * channel those changelogs are posted in.
     */
    private static Component bulletText(AiPolicyContent.Bullet bullet) {
        if ("gui.dungeontrain.ai_policy.used.3".equals(bullet.key())) {
            return Component.translatable(bullet.key(),
                    link(Component.translatable("gui.dungeontrain.ai_policy.used.3.link"), UPDATES_URL));
        }
        return Component.translatable(bullet.key());
    }

    private int addCentered(Component text, int y, int lh, int colour) {
        lines.add(new Line(text.getVisualOrderText(), y, true, 0, colour));
        return y + lh;
    }

    private int addCenteredWrapped(Component text, int y, int lh, int colour) {
        for (FormattedCharSequence line : this.font.split(text, colW)) {
            lines.add(new Line(line, y, true, 0, colour));
            y += lh;
        }
        return y;
    }

    private int addLeftWrapped(Component text, int y, int lh, int colour) {
        return addWrappedAt(text, colX, colW, y, lh, colour);
    }

    /** A single left-aligned line drawn at {@code x}. */
    private int addLineAt(FormattedCharSequence text, int x, int y, int lh, int colour) {
        lines.add(new Line(text, y, false, x, colour));
        return y + lh;
    }

    /** Text wrapped to {@code wrapW} and left-aligned at {@code x}. */
    private int addWrappedAt(Component text, int x, int wrapW, int y, int lh, int colour) {
        for (FormattedCharSequence line : this.font.split(text, wrapW)) {
            lines.add(new Line(line, y, false, x, colour));
            y += lh;
        }
        return y;
    }

    /** Style {@code label} as a blue, underlined, click-to-open-URL inline link. */
    private static Component link(MutableComponent label, String url) {
        return label.withStyle(s -> s
                .withColor(COLOUR_LINK)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)));
    }

    /** Open {@code url} through the vanilla confirm screen, returning to this page either way. */
    private void openLink(String url) {
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            if (yes) {
                Util.getPlatform().openUri(URI.create(url));
            }
            Minecraft.getInstance().setScreen(this);
        }, url, true));
    }

    /** The clickable {@link Style} under the given mouse position within the scrolled viewport, or null. */
    private Style styleAt(double mouseX, double mouseY) {
        if (mouseY < viewportTop || mouseY >= viewportBottom) {
            return null;
        }
        int lh = this.font.lineHeight;
        double canvasY = mouseY - viewportTop + scrollY;
        for (Line line : lines) {
            if (canvasY < line.canvasY() || canvasY >= line.canvasY() + lh) {
                continue;
            }
            int lineWidth = this.font.width(line.text());
            int startX = line.centered() ? this.width / 2 - lineWidth / 2 : line.x();
            if (mouseX < startX || mouseX >= startX + lineWidth) {
                continue;
            }
            return this.font.getSplitter().componentStyleAtWidth(line.text(), (int) (mouseX - startX));
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            Style style = styleAt(mouseX, mouseY);
            if (style != null && style.getClickEvent() != null
                    && style.getClickEvent().getAction() == ClickEvent.Action.OPEN_URL) {
                openLink(style.getClickEvent().getValue());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0) {
            this.scrollY = Mth.clamp(this.scrollY - (int) (scrollY * SCROLL_STEP), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Blurred menu panorama (vanilla), then a translucent panel behind the scrolling viewport so
        // text stays readable over the spinning background.
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(colX - PANEL_PAD, viewportTop - PANEL_PAD,
                colX + colW + PANEL_PAD, viewportBottom + PANEL_PAD, COLOUR_PANEL);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Draws the background (with our panel) and the Done widget.
        super.render(g, mouseX, mouseY, partialTick);

        int lh = this.font.lineHeight;
        g.enableScissor(colX - PANEL_PAD, viewportTop, colX + colW + PANEL_PAD, viewportBottom);

        // Cards first, so every rule, glyph and line below draws over its own panel.
        for (Card card : cards) {
            int drawY = viewportTop + card.canvasY() - scrollY;
            if (drawY + card.height() < viewportTop || drawY > viewportBottom) {
                continue;
            }
            g.fill(colX, drawY, colX + colW, drawY + card.height(), COLOUR_CARD_FILL);
            g.renderOutline(colX, drawY, colW, card.height(), COLOUR_CARD_BORDER);
        }

        for (Rule rule : rules) {
            int drawY = viewportTop + rule.canvasY() - scrollY;
            if (drawY + RULE_H < viewportTop || drawY > viewportBottom) {
                continue;
            }
            g.fill(rule.x(), drawY, rule.x() + rule.w(), drawY + RULE_H, rule.colour());
        }

        for (Icon icon : icons) {
            int drawY = viewportTop + icon.canvasY() - scrollY;
            if (drawY + ICON < viewportTop || drawY > viewportBottom) {
                continue;
            }
            g.renderItem(icon.stack(), icon.x(), drawY);
        }

        for (Line line : lines) {
            int drawY = viewportTop + line.canvasY() - scrollY;
            if (drawY + lh < viewportTop || drawY > viewportBottom) {
                continue; // cull off-viewport lines
            }
            int x = line.centered()
                    ? this.width / 2 - this.font.width(line.text()) / 2
                    : line.x();
            g.drawString(this.font, line.text(), x, drawY, line.colour(), false);
        }
        g.disableScissor();

        renderScrollbar(g);
    }

    /**
     * A track and thumb at the panel's right edge, drawn only when there is somewhere to scroll —
     * the page is long enough that without one there is no sign anything is below the fold.
     */
    private void renderScrollbar(GuiGraphics g) {
        if (maxScroll <= 0) {
            return;
        }
        int viewportH = viewportBottom - viewportTop;
        int x = colX + colW + PANEL_PAD - SCROLLBAR_W;
        g.fill(x, viewportTop, x + SCROLLBAR_W, viewportBottom, COLOUR_TRACK);

        int thumbH = Math.max(THUMB_MIN, viewportH * viewportH / Math.max(1, contentHeight));
        thumbH = Math.min(thumbH, viewportH);
        int thumbY = viewportTop + (viewportH - thumbH) * scrollY / maxScroll;
        g.fill(x, thumbY, x + SCROLLBAR_W, thumbY + thumbH, COLOUR_THUMB);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
