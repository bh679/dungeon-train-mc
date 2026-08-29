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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * The <b>AI Policy</b> page — a plain statement of what in Dungeon Train is and is not made by AI,
 * reachable from the Dungeon Train Options screen and from the Credits page's Made-by section.
 *
 * <p>Four sections, in the order a sceptical player wants them:</p>
 *
 * <ol>
 *   <li><b>Intro</b> — who built this and why.</li>
 *   <li><b>What is explicitly NOT AI</b> — the books, {@code @dev} chat, the PlayerMobs, the
 *       artwork, the name generator, the passenger log; and that nothing is trained on player
 *       data and no AI runs on the player's machine.</li>
 *   <li><b>Where AI is used</b> — content review, translation, changelogs, coding assistance and
 *       technical strings. The changelog line links out to the Discord updates channel.</li>
 *   <li><b>Which AI</b> — why Anthropic, and a closing thank-you.</li>
 * </ol>
 *
 * <p>Structurally a copy of {@link games.brennan.dungeontrain.client.credits.CreditsScreen}: the
 * column is laid out once in {@link #init} into a flat list of positioned {@link Line}s
 * (canvas-relative Y), then drawn in a scissor-clipped viewport in {@link #render} offset by
 * {@link #scrollY}. Inline links are hit-tested in {@link #mouseClicked} against the same lines and
 * opened through vanilla's {@link ConfirmLinkScreen}, returning here. {@code Done} is fixed below
 * the viewport and returns to whichever screen opened this one.</p>
 *
 * <p>The one thing Credits does not have is {@link #addBullet}: a bullet wraps to a narrower column
 * and its continuation lines are indented, so a two-line bullet hangs under its own text rather
 * than under the {@code •}.</p>
 */
public final class AiPolicyScreen extends Screen {

    private static final int MAX_COL_W   = 360;
    private static final int SIDE_MARGIN = 40;
    private static final int PANEL_PAD   = 10;
    private static final int TOP         = 16;
    private static final int HEADER_GAP  = 3;
    private static final int PARA_GAP    = 6;
    private static final int SECTION_GAP = 10;
    private static final int SCROLL_STEP = 12;

    /** Hanging indent for a bullet's continuation lines. */
    private static final int BULLET_INDENT = 8;

    private static final int COLOUR_PANEL  = 0xC0101010;
    private static final int COLOUR_HEADER = 0xFFFFFFFF;
    private static final int COLOUR_DESC   = 0xFFCACACA;
    /** Blue used for inline links (RGB, no alpha). */
    private static final int COLOUR_LINK   = 0x5B9BFF;

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
    private final List<Line> lines = new ArrayList<>();

    /**
     * One laid-out text line at a canvas-relative Y. {@code centered} lines are horizontally centred
     * on the screen (title/subtitle); the rest draw at {@code x}. The {@link FormattedCharSequence}
     * carries any inline-link {@link Style}, so both drawing and hit-testing use it directly.
     */
    private record Line(FormattedCharSequence text, int canvasY, boolean centered, int x, int colour) {}

    public AiPolicyScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.ai_policy.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
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

        // Intro — no header; it introduces the page rather than a section of it.
        y = addLeftWrapped(Component.translatable("gui.dungeontrain.ai_policy.intro.1"), y, lh, COLOUR_DESC);
        y += PARA_GAP;
        y = addLeftWrapped(Component.translatable("gui.dungeontrain.ai_policy.intro.2"), y, lh, COLOUR_DESC);
        y += SECTION_GAP;

        // The part players actually came for.
        y = addLeft(Component.translatable("gui.dungeontrain.ai_policy.not_ai.header"), y, lh, COLOUR_HEADER);
        y += HEADER_GAP;
        for (int i = 1; i <= 8; i++) {
            y = addBullet(Component.translatable("gui.dungeontrain.ai_policy.not_ai." + i), y, lh);
        }
        y += SECTION_GAP;

        y = addLeft(Component.translatable("gui.dungeontrain.ai_policy.used.header"), y, lh, COLOUR_HEADER);
        y += HEADER_GAP;
        y = addBullet(Component.translatable("gui.dungeontrain.ai_policy.used.1"), y, lh);
        y = addBullet(Component.translatable("gui.dungeontrain.ai_policy.used.2"), y, lh);
        // The changelog line ends in a link to the updates channel those changelogs are posted to.
        y = addBullet(Component.translatable("gui.dungeontrain.ai_policy.used.3",
                link(Component.translatable("gui.dungeontrain.ai_policy.used.3.link"), UPDATES_URL)), y, lh);
        y = addBullet(Component.translatable("gui.dungeontrain.ai_policy.used.4"), y, lh);
        y = addBullet(Component.translatable("gui.dungeontrain.ai_policy.used.5"), y, lh);
        y += SECTION_GAP;

        y = addLeft(Component.translatable("gui.dungeontrain.ai_policy.which.header"), y, lh, COLOUR_HEADER);
        y += HEADER_GAP;
        y = addLeftWrapped(Component.translatable("gui.dungeontrain.ai_policy.which.body"), y, lh, COLOUR_DESC);
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

    private int addLeft(Component text, int y, int lh, int colour) {
        lines.add(new Line(text.getVisualOrderText(), y, false, colX, colour));
        return y + lh;
    }

    private int addLeftWrapped(Component text, int y, int lh, int colour) {
        return addWrappedAt(text, colX, colW, y, lh, colour);
    }

    /**
     * One "• …" bullet: the marker sits in the column margin and every line of the bullet — the
     * first included — draws at the same indented x, so a wrapped bullet hangs under its own text.
     */
    private int addBullet(Component text, int y, int lh) {
        lines.add(new Line(Component.literal("•").getVisualOrderText(), y, false, colX, COLOUR_DESC));
        return addWrappedAt(text, colX + BULLET_INDENT, Math.max(1, colW - BULLET_INDENT), y, lh, COLOUR_DESC);
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
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
