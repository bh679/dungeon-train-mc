package games.brennan.dungeontrain.client.videotools;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.links.OfficialLinks;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.client.ui.CardCanvas;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * The <b>Video Tools</b> hub, opened from the title-screen button beside Train Editor. One card per
 * thing a creator came here for: the filming commands (a clickable, looping clip each — the clip
 * shows what the command does faster than a paragraph can), what an audience can send into the
 * stream, how to start a run from a genuinely clean slate, and where to post the result.
 *
 * <p>Layout, scrolling, clipping, the card/rule/glyph draw order, inline-link hit-testing and the
 * palette all live in {@link CardCanvas}, shared with the Credits and AI Policy pages so the four
 * cannot drift apart. The one thing this page draws itself is the animated clips: {@link CardCanvas}
 * knows how to blit a still texture but not a per-frame sprite sheet, so the {@link Tile} rects are
 * kept here in the same canvas space and drawn in their own scissored pass against
 * {@link CardCanvas#screenY}.</p>
 */
public final class VideoToolsScreen extends Screen {

    private static final int MAX_COL_W = 360;
    private static final int SIDE_MARGIN = 40;
    private static final int TOP = 16;
    private static final int TILE_GAP = 10;
    /** Breathing room each side of a tile's clip. The caption still spans the full half-column. */
    private static final int TILE_PAD = 12;
    /** Between a clip and the caption under it. */
    private static final int TILE_CAPTION_GAP = 6;

    /** Commands, so they read as something to type rather than prose. */
    private static final int COLOUR_COMMAND = 0xFFFFD37F;
    /** 1px frame around each tile — brightens on hover so the tile reads as clickable. */
    private static final int COLOUR_TILE_EDGE = 0xFF3A3A3A;
    private static final int COLOUR_TILE_EDGE_HOVER = 0xFFFFFFFF;

    /** Amber for the commands — the accent the Credits page gives the people who made it. */
    private static final int ACCENT_COMMANDS = 0xFFE0B56A;
    /** The page's link blue for the notes card, so the audience section reads as its own thing. */
    private static final int ACCENT_NOTES = 0xFF5B9BFF;
    /** Red for the card whose button deletes everything. */
    private static final int ACCENT_RESET = 0xFFCF5C5C;
    /** Green for the two "come talk to me" sections. */
    private static final int ACCENT_REACH = 0xFF5FBF5F;

    /**
     * The Death/Love Note rows, each with the item glyph drawn in its margin. Same shape as
     * {@code AiPolicyContent}'s bullets — content as data, so adding a row is one line.
     */
    private static final List<NoteRow> NOTE_ROWS = List.of(
            new NoteRow("notes.death", Items.WRITABLE_BOOK),
            new NoteRow("notes.love", Items.WRITTEN_BOOK),
            new NoteRow("notes.arrival", Items.PLAYER_HEAD),
            new NoteRow("notes.timing", Items.CLOCK),
            new NoteRow("notes.trophy", Items.BOOK),
            new NoteRow("notes.gate", Items.BARRIER));

    private record NoteRow(String key, Item glyph) {}

    /** One clickable command tile: its clip's rect in canvas space. */
    private record Tile(VideoTool tool, int x, int canvasY, int w, int h) {}

    private final Screen parent;
    private final CardCanvas canvas;
    private final List<Tile> tiles = new ArrayList<>();

    public VideoToolsScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.video_tools.title"));
        this.parent = parent;
        this.canvas = new CardCanvas(Minecraft.getInstance().font);
    }

    @Override
    protected void init() {
        tiles.clear();
        int colW = Math.min(MAX_COL_W, this.width - SIDE_MARGIN);
        canvas.beginLayout((this.width - colW) / 2, colW);

        int y = 0;

        // Title + subtitle, centred and un-carded — they frame the page.
        y = canvas.addCentered(this.title, y, CardCanvas.COLOUR_HEADER);
        y += CardCanvas.PARA_GAP;
        y = canvas.addCenteredWrapped(tr("subtitle"), y, CardCanvas.COLOUR_DESC);
        y += CardCanvas.SECTION_GAP;

        y = addCommandsCard(y);
        y += CardCanvas.CARD_GAP;

        y = addNotesCard(y);
        y += CardCanvas.CARD_GAP;

        // The reset lives on this page because it exists for filming: a creator shooting a first-run
        // video needs the mod to behave as if it had never been played, which no new world can do on
        // its own (the cross-world profile replays onto every world).
        y = addProseCard(y, ACCENT_RESET, tr("reset.header"), tr("reset.desc"));
        y += CardCanvas.CARD_GAP;

        y = addReachCard(y);

        // The viewport ends just above the button row so scrolling content never overlaps it.
        int rowY = this.height - 28;
        canvas.finishLayout(y, TOP, rowY - 8);

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

    /**
     * The filming-commands card: heading, accent bar, then the tiles side by side — clip, command,
     * blurb. Each caption is laid out in its own half-width column so a long blurb wraps inside its
     * tile rather than across both.
     */
    private int addCommandsCard(int top) {
        int innerX = canvas.colX() + CardCanvas.CARD_PAD;
        int innerW = Math.max(1, canvas.colW() - CardCanvas.CARD_PAD * 2);
        int y = cardHeading(top, innerX, innerW, tr("commands.header"), ACCENT_COMMANDS);

        int cellW = (innerW - TILE_GAP) / 2;
        // The clip is inset inside its cell; the caption keeps the full cell width beneath it.
        int clipW = Math.max(1, cellW - 2 * TILE_PAD);
        int clipH = clipW * VideoTool.FRAME_H / VideoTool.FRAME_W;

        int bottom = y;
        for (int i = 0; i < VideoTool.ALL.size(); i++) {
            VideoTool tool = VideoTool.ALL.get(i);
            int cellX = innerX + i * (cellW + TILE_GAP);
            tiles.add(new Tile(tool, cellX + TILE_PAD, y, clipW, clipH));

            int ty = y + clipH + TILE_CAPTION_GAP;
            ty = canvas.addWrappedAt(tool.header(), cellX, cellW, ty, CardCanvas.COLOUR_HEADER);
            ty = canvas.addWrappedAt(Component.literal(tool.command()), cellX, cellW, ty, COLOUR_COMMAND);
            ty = canvas.addWrappedAt(tool.blurb(), cellX, cellW, ty, CardCanvas.COLOUR_DESC);
            bottom = Math.max(bottom, ty);
        }

        return closeCard(top, bottom);
    }

    /**
     * The Death/Love Notes card: what a viewer can send into the stream from their own game. A lead
     * paragraph, then one glyphed row per thing a creator needs to know before promising it on air.
     */
    private int addNotesCard(int top) {
        int lh = canvas.lineHeight();
        int innerX = canvas.colX() + CardCanvas.CARD_PAD;
        int innerW = Math.max(1, canvas.colW() - CardCanvas.CARD_PAD * 2);
        int y = cardHeading(top, innerX, innerW, tr("notes.header"), ACCENT_NOTES);

        y = canvas.addWrappedAt(tr("notes.desc"), innerX, innerW, y, CardCanvas.COLOUR_DESC);
        y += CardCanvas.ROW_GAP;

        int textX = innerX + CardCanvas.ICON + CardCanvas.ICON_GAP;
        int textW = Math.max(1, innerW - CardCanvas.ICON - CardCanvas.ICON_GAP);
        for (int i = 0; i < NOTE_ROWS.size(); i++) {
            NoteRow row = NOTE_ROWS.get(i);
            // Centre the glyph on the row's FIRST line, not on the whole row — a three-line row with
            // a vertically-centred icon reads as though the icon belongs to the middle line.
            int iconTop = y - (CardCanvas.ICON - lh) / 2;
            canvas.addIcon(new ItemStack(row.glyph()), innerX, iconTop);

            int textBottom = canvas.addWrappedAt(tr(row.key()), textX, textW, y, CardCanvas.COLOUR_DESC);
            y = Math.max(textBottom, iconTop + CardCanvas.ICON);
            if (i < NOTE_ROWS.size() - 1) {
                y += CardCanvas.ROW_GAP;
            }
        }

        return closeCard(top, y);
    }

    /**
     * The last card: where the finished video goes, then how to reach the dev, separated by a
     * hairline rather than split into two cards — they are the same "come talk to me" beat.
     */
    private int addReachCard(int top) {
        int innerX = canvas.colX() + CardCanvas.CARD_PAD;
        int innerW = Math.max(1, canvas.colW() - CardCanvas.CARD_PAD * 2);
        int y = cardHeading(top, innerX, innerW, tr("share.header"), ACCENT_REACH);

        // "…on the Discord" is an inline link so the channel is reachable without scrolling back
        // down to the button.
        y = canvas.addWrappedAt(tr("share.desc", link(Component.literal("Discord"), OfficialLinks.discord())),
                innerX, innerW, y, CardCanvas.COLOUR_DESC);

        y += CardCanvas.ROW_GAP;
        y = canvas.addDivider(innerX, y, innerW);
        y += CardCanvas.ROW_GAP;

        y = canvas.addWrappedAt(tr("help.header"), innerX, innerW, y, CardCanvas.COLOUR_HEADER);
        y = canvas.addWrappedAt(tr("help.desc"), innerX, innerW, y, CardCanvas.COLOUR_DESC);

        return closeCard(top, y);
    }

    /** A card that is one heading over one wrapped paragraph. */
    private int addProseCard(int top, int accent, Component header, Component body) {
        int innerX = canvas.colX() + CardCanvas.CARD_PAD;
        int innerW = Math.max(1, canvas.colW() - CardCanvas.CARD_PAD * 2);
        int y = cardHeading(top, innerX, innerW, header, accent);
        y = canvas.addWrappedAt(body, innerX, innerW, y, CardCanvas.COLOUR_DESC);
        return closeCard(top, y);
    }

    /**
     * A card's heading and accent bar. Wrapped rather than a single line: at GUI Scale 4 a heading
     * can be wider than the card, and a heading running out past its own border is the one overflow
     * a reader cannot miss. Returns the canvas Y of the card's first body row.
     */
    private int cardHeading(int top, int innerX, int innerW, Component header, int accent) {
        int y = canvas.addWrappedAt(header, innerX, innerW, top + CardCanvas.CARD_PAD,
                CardCanvas.COLOUR_HEADER);
        y += CardCanvas.RULE_GAP;
        y = canvas.addRule(innerX, y, Math.min(CardCanvas.RULE_W, innerW), accent);
        return y + CardCanvas.RULE_TO_BODY;
    }

    /** Close a card whose contents ended at {@code contentBottom}. Returns the Y below its border. */
    private int closeCard(int top, int contentBottom) {
        int bottom = contentBottom + CardCanvas.CARD_PAD;
        canvas.addCard(top, bottom - top);
        return bottom;
    }

    /** Open the type-to-confirm screen. Nothing is deleted until the player confirms there. */
    private void openReset() {
        UiAnalytics.click(UiAnalytics.SURFACE_TITLE_SCREEN, UiAnalytics.TARGET_VIDEO_TOOLS_RESET);
        Minecraft.getInstance().setScreen(new ResetProgressScreen(this));
    }

    private static MutableComponent tr(String suffix) {
        return Component.translatable("gui.dungeontrain.video_tools." + suffix);
    }

    private static MutableComponent tr(String suffix, Object... args) {
        return Component.translatable("gui.dungeontrain.video_tools." + suffix, args);
    }

    /** Style {@code label} as a blue, underlined, click-to-open-URL inline link. */
    private static Component link(MutableComponent label, String url) {
        return label.withStyle(s -> s
                .withColor(CardCanvas.COLOUR_LINK)
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
        if (mouseY < canvas.viewportTop() || mouseY >= canvas.viewportBottom()) {
            return null;
        }
        for (Tile tile : tiles) {
            int drawY = canvas.screenY(tile.canvasY());
            if (mouseY >= drawY && mouseY < drawY + tile.h()
                    && mouseX >= tile.x() && mouseX < tile.x() + tile.w()) {
                return tile;
            }
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
            Style style = canvas.styleAt(mouseX, mouseY, this.width);
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
        return canvas.scroll(scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Blurred menu panorama (vanilla), then the canvas's own translucent panel so text stays
        // readable over the spinning background.
        super.renderBackground(g, mouseX, mouseY, partialTick);
        canvas.renderPanel(g);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Draws the background (with our panel) and the button row.
        super.render(g, mouseX, mouseY, partialTick);
        canvas.render(g, this.width);

        // The clips, after the canvas pass so the card's translucent fill sits BEHIND them rather
        // than tinting them. Clipped to the content column, which keeps them off the scrollbar.
        Tile hovered = tileAt(mouseX, mouseY);
        g.enableScissor(canvas.colX(), canvas.viewportTop(),
                canvas.colX() + canvas.colW(), canvas.viewportBottom());
        for (Tile tile : tiles) {
            int drawY = canvas.screenY(tile.canvasY());
            if (drawY + tile.h() < canvas.viewportTop() || drawY > canvas.viewportBottom()) {
                continue; // cull off-viewport clips
            }
            AnimatedSheet.draw(g, tile.tool(), tile.x(), drawY, tile.w(), tile.h());
            g.renderOutline(tile.x() - 1, drawY - 1, tile.w() + 2, tile.h() + 2,
                    tile == hovered ? COLOUR_TILE_EDGE_HOVER : COLOUR_TILE_EDGE);
        }
        g.disableScissor();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
