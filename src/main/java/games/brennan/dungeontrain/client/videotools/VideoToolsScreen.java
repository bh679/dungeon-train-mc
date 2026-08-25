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
 * The <b>Video Tools</b> hub, opened from the title-screen button beside Train Editor. Leads with
 * a clickable, looping tile per filming command — the clip shows what the command does faster than
 * a paragraph can — with the command and a one-line blurb under each. Clicking a tile opens its
 * {@link VideoToolDetailScreen}. Below the tiles: where to post what you make, and how to reach
 * the dev.
 *
 * <p>Layout and scrolling follow {@code credits.CreditsScreen}: content is laid out once in
 * {@link #init} into positioned {@link Line}s at canvas-relative Y, drawn in a scissor-clipped
 * viewport offset by {@link #scrollY}, with inline links hit-tested against the same lines and
 * opened through vanilla's {@link ConfirmLinkScreen}. The tiles are {@link Tile} rects in that
 * same canvas space, so they scroll and hit-test with the text.</p>
 */
public final class VideoToolsScreen extends Screen {

    private static final int MAX_COL_W = 360;
    private static final int SIDE_MARGIN = 40;
    private static final int PANEL_PAD = 10;
    private static final int TOP = 16;
    private static final int HEADER_GAP = 3;
    private static final int SECTION_GAP = 10;
    private static final int TILE_GAP = 10;
    /** Breathing room each side of a tile's clip. The caption still spans the full half-column. */
    private static final int TILE_PAD = 16;
    private static final int SCROLL_STEP = 12;

    private static final int COLOUR_PANEL = 0xC0101010;
    private static final int COLOUR_HEADER = 0xFFFFFFFF;
    private static final int COLOUR_DESC = 0xFFCACACA;
    /** Commands, so they read as something to type rather than prose. */
    private static final int COLOUR_COMMAND = 0xFFFFD37F;
    /** Blue used for inline links (RGB, no alpha). */
    private static final int COLOUR_LINK = 0x5B9BFF;
    /** 1px frame around each tile — brightens on hover so the tile reads as clickable. */
    private static final int COLOUR_TILE_EDGE = 0xFF3A3A3A;
    private static final int COLOUR_TILE_EDGE_HOVER = 0xFFFFFFFF;

    private final Screen parent;

    private int colX;
    private int colW;
    private int viewportTop;
    private int viewportBottom;
    private int contentHeight;
    private int scrollY;
    private int maxScroll;
    private final List<Line> lines = new ArrayList<>();
    private final List<Tile> tiles = new ArrayList<>();

    private record Line(FormattedCharSequence text, int canvasY, boolean centered, int x, int wrapW, int colour) {}

    /** One clickable command tile: its clip's rect in canvas space. */
    private record Tile(VideoTool tool, int x, int canvasY, int w, int h) {}

    public VideoToolsScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.video_tools.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        lines.clear();
        tiles.clear();
        colW = Math.min(MAX_COL_W, this.width - SIDE_MARGIN);
        colX = (this.width - colW) / 2;
        int lh = this.font.lineHeight;

        int y = 0;

        y = addCentered(this.title, y, lh, COLOUR_HEADER);
        y += 6;
        y = addCenteredWrapped(tr("subtitle"), y, lh, COLOUR_DESC);
        y += SECTION_GAP;

        y = addTileRow(y, lh);
        y += SECTION_GAP;

        // The reset lives on this page because it exists for filming: a creator shooting a first-run
        // video needs the mod to behave as if it had never been played, which no new world can do on
        // its own (the cross-world profile replays onto every world).
        y = addLeftWrapped(tr("reset.header"), y, lh, COLOUR_HEADER);
        y += HEADER_GAP;
        y = addLeftWrapped(tr("reset.desc"), y, lh, COLOUR_DESC);
        y += SECTION_GAP;

        // Where the finished video goes. "…on the Discord" is an inline link so the channel is
        // reachable without scrolling back to the button.
        y = addLeftWrapped(tr("share.header"), y, lh, COLOUR_HEADER);
        y += HEADER_GAP;
        y = addLeftWrapped(tr("share.desc", link(Component.literal("Discord"), OfficialLinks.discord())),
                y, lh, COLOUR_DESC);
        y += SECTION_GAP;

        y = addLeftWrapped(tr("help.header"), y, lh, COLOUR_HEADER);
        y += HEADER_GAP;
        y = addLeftWrapped(tr("help.desc"), y, lh, COLOUR_DESC);
        y += SECTION_GAP;

        contentHeight = y;

        int rowY = this.height - 28;
        viewportTop = TOP;
        viewportBottom = rowY - 8;
        if (viewportBottom < viewportTop) {
            viewportBottom = viewportTop;
        }
        maxScroll = Math.max(0, contentHeight - (viewportBottom - viewportTop));
        scrollY = Mth.clamp(scrollY, 0, maxScroll);

        int gap = 4;
        int resetW = 120;
        int discordW = 110;
        int doneW = 70;
        int rowX = (this.width - (resetW + discordW + doneW + 2 * gap)) / 2;

        DarkTintedButton reset = new DarkTintedButton(rowX, rowY, resetW, 20,
                tr("reset.button"), b -> openReset());
        // Title-screen-only today, so this never fires; the guard is here so putting the page on the
        // pause menu later cannot hand someone a world-deleting button while that world is loaded.
        reset.active = Minecraft.getInstance().level == null;
        addRenderableWidget(reset);
        addRenderableWidget(new DarkTintedButton(rowX + resetW + gap, rowY, discordW, 20,
                tr("discord_button"), b -> openDiscord()));
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(rowX + resetW + discordW + 2 * gap, rowY, doneW, 20)
                .build());
    }

    /** Open the type-to-confirm screen. Nothing is deleted until the player confirms there. */
    private void openReset() {
        UiAnalytics.click(UiAnalytics.SURFACE_TITLE_SCREEN, UiAnalytics.TARGET_VIDEO_TOOLS_RESET);
        Minecraft.getInstance().setScreen(new ResetProgressScreen(this));
    }

    /**
     * The two command tiles, side by side: clip, command, blurb. Both captions are laid out in
     * their own half-width column so a long blurb wraps inside its tile rather than across both.
     * Returns the canvas Y below the taller of the two.
     */
    private int addTileRow(int y, int lh) {
        int cellW = (colW - TILE_GAP) / 2;
        // The clip is inset inside its cell; the caption keeps the full cell width beneath it.
        int clipW = Math.max(1, cellW - 2 * TILE_PAD);
        int clipH = clipW * VideoTool.FRAME_H / VideoTool.FRAME_W;

        int bottom = y;
        for (int i = 0; i < VideoTool.ALL.size(); i++) {
            VideoTool tool = VideoTool.ALL.get(i);
            int cellX = colX + i * (cellW + TILE_GAP);
            tiles.add(new Tile(tool, cellX + TILE_PAD, y, clipW, clipH));

            int ty = y + clipH + 6;
            ty = addWrappedAt(tool.header(), cellX, cellW, ty, lh, COLOUR_HEADER);
            ty = addWrappedAt(Component.literal(tool.command()), cellX, cellW, ty, lh, COLOUR_COMMAND);
            ty = addWrappedAt(tool.blurb(), cellX, cellW, ty, lh, COLOUR_DESC);
            bottom = Math.max(bottom, ty);
        }
        return bottom;
    }

    private static MutableComponent tr(String suffix) {
        return Component.translatable("gui.dungeontrain.video_tools." + suffix);
    }

    private static MutableComponent tr(String suffix, Object... args) {
        return Component.translatable("gui.dungeontrain.video_tools." + suffix, args);
    }

    private int addCentered(Component text, int y, int lh, int colour) {
        lines.add(new Line(text.getVisualOrderText(), y, true, 0, colW, colour));
        return y + lh;
    }

    private int addCenteredWrapped(Component text, int y, int lh, int colour) {
        for (FormattedCharSequence line : this.font.split(text, colW)) {
            lines.add(new Line(line, y, true, 0, colW, colour));
            y += lh;
        }
        return y;
    }

    private int addLeftWrapped(Component text, int y, int lh, int colour) {
        return addWrappedAt(text, colX, colW, y, lh, colour);
    }

    private int addWrappedAt(Component text, int x, int wrapW, int y, int lh, int colour) {
        for (FormattedCharSequence line : this.font.split(text, wrapW)) {
            lines.add(new Line(line, y, false, x, wrapW, colour));
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

    /** The tile under the given mouse position within the scrolled viewport, or null. */
    private Tile tileAt(double mouseX, double mouseY) {
        if (mouseY < viewportTop || mouseY >= viewportBottom) {
            return null;
        }
        double canvasY = mouseY - viewportTop + scrollY;
        for (Tile tile : tiles) {
            if (canvasY >= tile.canvasY() && canvasY < tile.canvasY() + tile.h()
                    && mouseX >= tile.x() && mouseX < tile.x() + tile.w()) {
                return tile;
            }
        }
        return null;
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
            Tile tile = tileAt(mouseX, mouseY);
            if (tile != null) {
                Minecraft.getInstance().setScreen(new VideoToolDetailScreen(this, tile.tool()));
                return true;
            }
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
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(colX - PANEL_PAD, viewportTop - PANEL_PAD,
                colX + colW + PANEL_PAD, viewportBottom + PANEL_PAD, COLOUR_PANEL);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int lh = this.font.lineHeight;
        Tile hovered = tileAt(mouseX, mouseY);
        g.enableScissor(colX - PANEL_PAD, viewportTop, colX + colW + PANEL_PAD, viewportBottom);

        for (Tile tile : tiles) {
            int drawY = viewportTop + tile.canvasY() - scrollY;
            if (drawY + tile.h() < viewportTop || drawY > viewportBottom) {
                continue; // cull off-viewport clips
            }
            AnimatedSheet.draw(g, tile.tool(), tile.x(), drawY, tile.w(), tile.h());
            g.renderOutline(tile.x() - 1, drawY - 1, tile.w() + 2, tile.h() + 2,
                    tile == hovered ? COLOUR_TILE_EDGE_HOVER : COLOUR_TILE_EDGE);
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
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
