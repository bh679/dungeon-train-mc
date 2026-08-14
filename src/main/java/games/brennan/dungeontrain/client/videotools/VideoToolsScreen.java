package games.brennan.dungeontrain.client.videotools;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.links.OfficialLinks;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
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
 * The <b>Video Tools</b> page, opened from the title-screen spyglass icon (see
 * {@code TitleScreenCreditsButton}, which owns the DT icon column). Everything a
 * content creator needs to film Dungeon Train, in four sections:
 *
 * <ol>
 *   <li><b>Cinematographer mode</b> — {@code /dt cinematographer}, its door radius,
 *       and the clear-view sub-mode.</li>
 *   <li><b>Replay the intro cinematic</b> — {@code /dt cinematic}, its two start
 *       modes, and the C hotkey.</li>
 *   <li><b>Worth knowing</b> — both commands need cheats, and cinematographer mode
 *       flags the run as Free Play while {@code cinematic} does not.</li>
 *   <li><b>Share it / get help</b> — the Discord media channel, and {@code @dev}.</li>
 * </ol>
 *
 * <p>Layout and scrolling mirror {@code credits.CreditsScreen} exactly: the content
 * column is laid out once in {@link #init} into a flat list of positioned {@link Line}s
 * (canvas-relative Y), then drawn in a scissor-clipped viewport in {@link #render}
 * offset by {@link #scrollY}. Inline links are hit-tested in {@link #mouseClicked}
 * against the same lines and opened through vanilla's {@link ConfirmLinkScreen},
 * returning here. The Discord and Done buttons are fixed below the viewport.</p>
 */
public final class VideoToolsScreen extends Screen {

    private static final int MAX_COL_W = 360;
    private static final int SIDE_MARGIN = 40;
    private static final int PANEL_PAD = 10;
    private static final int TOP = 16;
    private static final int HEADER_GAP = 3;
    private static final int SECTION_GAP = 10;
    private static final int SCROLL_STEP = 12;

    private static final int COLOUR_PANEL = 0xC0101010;
    private static final int COLOUR_HEADER = 0xFFFFFFFF;
    private static final int COLOUR_DESC = 0xFFCACACA;
    /** Commands, so they read as something to type rather than prose. */
    private static final int COLOUR_COMMAND = 0xFFFFD37F;
    /** Blue used for inline links (RGB, no alpha). */
    private static final int COLOUR_LINK = 0x5B9BFF;

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
     * One laid-out text line at a canvas-relative Y. {@code centered} lines are horizontally
     * centred on the screen (title/subtitle); the rest draw at the content column. The
     * {@link FormattedCharSequence} carries any inline-link {@link Style}, so both drawing and
     * hit-testing use it directly.
     */
    private record Line(FormattedCharSequence text, int canvasY, boolean centered, int colour) {}

    public VideoToolsScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.video_tools.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        lines.clear();
        colW = Math.min(MAX_COL_W, this.width - SIDE_MARGIN);
        colX = (this.width - colW) / 2;
        int lh = this.font.lineHeight;

        int y = 0;

        y = addCentered(this.title, y, lh, COLOUR_HEADER);
        y += 6;
        y = addCenteredWrapped(tr("subtitle"), y, lh, COLOUR_DESC);
        y += SECTION_GAP;

        // 1. Cinematographer mode.
        y = addLeftWrapped(tr("cinematographer.header"), y, lh, COLOUR_HEADER);
        y += HEADER_GAP;
        y = addLeftWrapped(Component.literal("/dt cinematographer"), y, lh, COLOUR_COMMAND);
        y = addLeftWrapped(tr("cinematographer.desc"), y, lh, COLOUR_DESC);
        y = addLeftWrapped(tr("cinematographer.distance"), y, lh, COLOUR_DESC);
        y = addLeftWrapped(tr("cinematographer.clearview"), y, lh, COLOUR_DESC);
        y += SECTION_GAP;

        // 2. Intro cinematic replay.
        y = addLeftWrapped(tr("cinematic.header"), y, lh, COLOUR_HEADER);
        y += HEADER_GAP;
        y = addLeftWrapped(Component.literal("/dt cinematic"), y, lh, COLOUR_COMMAND);
        y = addLeftWrapped(tr("cinematic.desc"), y, lh, COLOUR_DESC);
        y = addLeftWrapped(tr("cinematic.hotkey"), y, lh, COLOUR_DESC);
        y += SECTION_GAP;

        // 3. The gotcha — cinematographer mode taints the run into Free Play.
        y = addLeftWrapped(tr("notes.header"), y, lh, COLOUR_HEADER);
        y += HEADER_GAP;
        y = addLeftWrapped(tr("notes.free_play"), y, lh, COLOUR_DESC);
        y += SECTION_GAP;

        // 4. Where the finished video goes, and how to reach the dev.
        y = addLeftWrapped(tr("share.header"), y, lh, COLOUR_HEADER);
        y += HEADER_GAP;
        // "…on the Discord" is the inline link, so the channel can be reached without
        // scrolling back down to the button.
        y = addLeftWrapped(tr("share.desc", link(Component.literal("Discord"), OfficialLinks.discord())),
                y, lh, COLOUR_DESC);
        y += SECTION_GAP;

        y = addLeftWrapped(tr("help.header"), y, lh, COLOUR_HEADER);
        y += HEADER_GAP;
        y = addLeftWrapped(tr("help.desc"), y, lh, COLOUR_DESC);
        y += SECTION_GAP;

        contentHeight = y;

        // One bottom row: "Join the Discord" beside Done. The viewport ends just above the
        // row so scrolling content never overlaps the buttons.
        int rowY = this.height - 28;
        viewportTop = TOP;
        viewportBottom = rowY - 8;
        if (viewportBottom < viewportTop) {
            viewportBottom = viewportTop;
        }
        maxScroll = Math.max(0, contentHeight - (viewportBottom - viewportTop));
        scrollY = Mth.clamp(scrollY, 0, maxScroll);

        int gap = 4;
        int discordW = 150;
        int doneW = 100;
        int rowX = (this.width - (discordW + gap + doneW)) / 2;

        addRenderableWidget(new DarkTintedButton(rowX, rowY, discordW, 20,
                tr("discord_button"), b -> openDiscord()));

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(rowX + discordW + gap, rowY, doneW, 20)
                .build());
    }

    private static MutableComponent tr(String suffix) {
        return Component.translatable("gui.dungeontrain.video_tools." + suffix);
    }

    private static MutableComponent tr(String suffix, Object... args) {
        return Component.translatable("gui.dungeontrain.video_tools." + suffix, args);
    }

    private int addCentered(Component text, int y, int lh, int colour) {
        lines.add(new Line(text.getVisualOrderText(), y, true, colour));
        return y + lh;
    }

    private int addCenteredWrapped(Component text, int y, int lh, int colour) {
        for (FormattedCharSequence line : this.font.split(text, colW)) {
            lines.add(new Line(line, y, true, colour));
            y += lh;
        }
        return y;
    }

    private int addLeftWrapped(Component text, int y, int lh, int colour) {
        for (FormattedCharSequence line : this.font.split(text, colW)) {
            lines.add(new Line(line, y, false, colour));
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

    /** Same funnel event as every other Discord affordance on the menu — read the URL at click time. */
    private void openDiscord() {
        UiAnalytics.click(UiAnalytics.SURFACE_TITLE_SCREEN, UiAnalytics.TARGET_DISCORD);
        String discordUrl = OfficialLinks.discord();
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            UiAnalytics.confirm(UiAnalytics.SURFACE_TITLE_SCREEN, UiAnalytics.TARGET_DISCORD, yes);
            if (yes) {
                Util.getPlatform().openUri(URI.create(discordUrl));
            }
            Minecraft.getInstance().setScreen(this);
        }, discordUrl, true));
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
            int startX = line.centered() ? this.width / 2 - lineWidth / 2 : colX;
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
        // Blurred menu panorama (vanilla), then a translucent panel behind the scrolling
        // viewport so text stays readable over the spinning background.
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(colX - PANEL_PAD, viewportTop - PANEL_PAD,
                colX + colW + PANEL_PAD, viewportBottom + PANEL_PAD, COLOUR_PANEL);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Draws the background (with our panel) and the bottom-row widgets.
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
                    : colX;
            g.drawString(this.font, line.text(), x, drawY, line.colour(), false);
        }
        g.disableScissor();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
