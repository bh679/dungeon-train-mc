package games.brennan.dungeontrain.client.videos;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.links.OfficialLinks;
import games.brennan.dungeontrain.client.menu.BilibiliIconButton;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.client.menu.DiscordIconButton;
import games.brennan.dungeontrain.client.menu.InstagramIconButton;
import games.brennan.dungeontrain.client.menu.YouTubeIconButton;
import games.brennan.dungeontrain.client.videotools.VideoToolsScreen;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.util.Mth;
import org.joml.Vector2i;
import org.joml.Vector2ic;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * The <b>Videos</b> page, opened from the title screen's icon column: every video about Dungeon
 * Train the relay has saved — YouTube, Bilibili, Twitch, Instagram — with one toolbar row over the
 * list. A row opens the video in the browser through vanilla's link-confirm screen.
 *
 * <p>The toolbar, left to right: one <b>icon toggle per platform</b> present (lit = shown), the
 * <b>★ dev-faves</b> toggle, the <b>uploader box</b> — type to narrow, with a suggestion list under
 * it that starts as every uploader and shrinks to matches (the shape of the editor's builder
 * search) — and the <b>sort</b> cycle button. Every icon carries a tooltip saying what it is and
 * which way it is set. Filter and sort state survive a resize (fields on the screen); the catalogue
 * survives the session ({@link VideoCatalog}).</p>
 *
 * <p>Three centre-states stand in for the list when it has nothing to show: loading, failed (with
 * a Retry button), and "nothing matches these filters". The data arrives on an HTTP thread and is
 * handed back here via {@link #onCatalogChanged()} on the render thread.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class VideosScreen extends Screen {

    private static final int MARGIN = 16;
    private static final int GAP = 4;
    private static final int TOP = 32;
    private static final int BUTTON_H = 20;
    private static final int ICON = 20;
    private static final int SORT_W = 110;
    private static final int UPLOADER_MIN_W = 80;
    private static final int BOTTOM_ROW_H = 20;
    private static final int MAX_QUERY = 64;
    private static final int SUB_COLOUR = 0xFF9A9A9A;
    private static final int ERROR_COLOUR = 0xFFCF5C5C;

    private final Screen parent;

    private VideoQuery.Filter filter = VideoQuery.Filter.ALL;
    private VideoQuery.Sort sort = VideoQuery.Sort.VIEWS;

    private final List<PlatformToggleButton> platformButtons = new ArrayList<>();
    private StarToggleButton starButton;
    private EditBox uploaderBox;
    private UploaderDropdown dropdown;
    private Button sortButton;
    private Button retryButton;
    private VideoList list;

    public VideosScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.videos.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        VideoCatalog.ensureFetched();
        platformButtons.clear();

        int rowW = this.width - 2 * MARGIN;
        int x = MARGIN;

        // Platform toggles — one per platform the catalogue actually has (all five before it loads,
        // so the row does not jump when the data arrives and drops a platform nobody posted on).
        List<VideoEntry.Platform> platforms = VideoCatalog.state() == VideoCatalog.State.LOADED
                ? VideoQuery.platforms(VideoCatalog.entries()) : List.of(VideoEntry.Platform.values());
        for (VideoEntry.Platform p : platforms) {
            PlatformToggleButton b = new PlatformToggleButton(x, TOP, ICON, p, () -> filter.has(p),
                    btn -> togglePlatform(p));
            platformButtons.add(addRenderableWidget(b));
            x += ICON + GAP;
        }

        starButton = addRenderableWidget(new StarToggleButton(x, TOP, ICON, () -> filter.devFavOnly(),
                b -> toggleDevFav()));
        x += ICON + GAP;

        // Sort takes the right end; the uploader box gets whatever is left between.
        int sortW = Math.min(SORT_W, Math.max(60, rowW / 4));
        int sortX = MARGIN + rowW - sortW;
        int uploaderW = Math.max(UPLOADER_MIN_W, sortX - GAP - x);
        if (x + uploaderW + GAP > sortX) {
            // Very narrow window: let the box take the minimum and the sort button shrink to fit.
            sortW = Math.max(40, MARGIN + rowW - (x + uploaderW + GAP));
            sortX = MARGIN + rowW - sortW;
        }

        EditBox box = new EditBox(this.font, x, TOP, uploaderW, BUTTON_H,
                Component.translatable("gui.dungeontrain.videos.filter.uploader"));
        box.setMaxLength(MAX_QUERY);
        box.setValue(filter.channelQuery());   // survives a resize
        Component hint = Component.translatable("gui.dungeontrain.videos.filter.uploader.hint");
        box.setHint(this.font.width(hint) <= uploaderW - 8 ? hint : Component.empty());
        // No vanilla tooltip on the box: that positions itself below the cursor, straight over the
        // suggestion panel. Its tooltip is drawn by render() ABOVE the box instead.
        box.setResponder(text -> {
            filter = filter.withChannelQuery(text);
            refresh();
        });
        uploaderBox = addRenderableWidget(box);

        dropdown = new UploaderDropdown(this.font, this::pickUploader);
        dropdown.place(x, TOP + BUTTON_H, uploaderW);

        sortButton = addRenderableWidget(new DarkTintedButton(sortX, TOP, sortW, BUTTON_H,
                CommonComponents.EMPTY, b -> cycleSort()));

        int listTop = TOP + BUTTON_H + GAP;
        int listBottom = this.height - MARGIN - BOTTOM_ROW_H - GAP;
        list = addRenderableWidget(new VideoList(this.font, MARGIN, listTop, rowW, listBottom - listTop,
                this::open, this::flag));
        // The suggestion panel hangs over the list: while it is open, the rows under it neither
        // highlight nor answer clicks.
        list.setCoveredBy((mx, my) -> dropdown.isMouseOver(mx, my));

        // Retry sits in the middle of the (empty) list and only shows when the fetch failed.
        retryButton = addRenderableWidget(new DarkTintedButton(this.width / 2 - 50,
                (listTop + listBottom) / 2 + this.font.lineHeight, 100, BUTTON_H,
                Component.translatable("gui.dungeontrain.videos.retry"), b -> {
                    VideoCatalog.retry();
                    refresh();
                }));

        // Bottom row: Creator Tools (the Video Tools page under the name that says who it is for) |
        // Submit a video (a link into the operator's review queue) | Done.
        int bottomY = this.height - MARGIN - BOTTOM_ROW_H;

        // Brennan's channels, bottom-right: YouTube · Bilibili · Instagram · Discord. Icons, not
        // words — they are the marks the page's own rows already taught. Each opens through the
        // vanilla confirm screen and comes back here.
        int channelsW = 4 * ICON + 3 * GAP;
        int cx = MARGIN + rowW - channelsW;
        addChannelIcon(new YouTubeIconButton(cx, bottomY, ICON, Component.translatable("gui.dungeontrain.videos.channels.youtube"),
                b -> openChannel(UiAnalytics.TARGET_YOUTUBE, OfficialLinks.youtube())), "youtube");
        addChannelIcon(new BilibiliIconButton(cx + (ICON + GAP), bottomY, ICON, Component.translatable("gui.dungeontrain.videos.channels.bilibili"),
                b -> openChannel(UiAnalytics.TARGET_BILIBILI, OfficialLinks.bilibili())), "bilibili");
        addChannelIcon(new InstagramIconButton(cx + 2 * (ICON + GAP), bottomY, ICON, Component.translatable("gui.dungeontrain.videos.channels.instagram"),
                b -> openChannel(UiAnalytics.TARGET_INSTAGRAM, OfficialLinks.instagram())), "instagram");
        addChannelIcon(new DiscordIconButton(cx + 3 * (ICON + GAP), bottomY, ICON, Component.translatable("gui.dungeontrain.videos.channels.discord"),
                b -> openChannel(UiAnalytics.TARGET_DISCORD, OfficialLinks.discord())), "discord");

        // The three text buttons centre in what is left of the row to the icons' left.
        int trioRight = cx - 2 * GAP;
        int bottomW = Math.min(3 * 100 + 2 * GAP, trioRight - MARGIN);
        int thirdW = (bottomW - 2 * GAP) / 3;
        int bx = MARGIN + (trioRight - MARGIN - bottomW) / 2;
        Button tools = addRenderableWidget(new DarkTintedButton(bx, bottomY, thirdW, BOTTOM_ROW_H,
                Component.translatable("gui.dungeontrain.videos.creator_tools"), b -> {
                    UiAnalytics.click(UiAnalytics.SURFACE_VIDEOS, UiAnalytics.TARGET_VIDEO_TOOLS);
                    Minecraft.getInstance().setScreen(new VideoToolsScreen(this));
                }));
        tools.setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.videos.tools.tooltip")));
        Button submit = addRenderableWidget(new DarkTintedButton(bx + thirdW + GAP, bottomY, thirdW, BOTTOM_ROW_H,
                Component.translatable("gui.dungeontrain.videos.submit.button"), b -> {
                    UiAnalytics.click(UiAnalytics.SURFACE_VIDEOS, UiAnalytics.TARGET_VIDEO_SUBMIT);
                    Minecraft.getInstance().setScreen(new VideoSubmitScreen(this));
                }));
        submit.setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.videos.submit.tooltip")));
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(bx + 2 * (thirdW + GAP), bottomY, bottomW - 2 * (thirdW + GAP), BOTTOM_ROW_H)
                .build());

        refresh();
    }

    /** Called on the render thread when the catalogue loads or fails. */
    public void onCatalogChanged() {
        // The platform row was built for all five; rebuild it for the ones that exist.
        rebuildWidgets();
    }

    // ---- filter / sort controls -------------------------------------------------

    private void togglePlatform(VideoEntry.Platform p) {
        filter = filter.togglePlatform(p);
        refresh();
    }

    private void toggleDevFav() {
        filter = filter.withDevFavOnly(!filter.devFavOnly());
        refresh();
    }

    private void cycleSort() {
        sort = sort.next();
        refresh();
    }

    /** A suggestion was clicked: it becomes the box's text, and focus (so the list) goes away. */
    private void pickUploader(String name) {
        uploaderBox.setValue(name);       // fires the responder → filter + refresh
        uploaderBox.setFocused(false);
        setFocused(null);
    }

    /** Relabel every control and rebuild the list from the current catalogue + filter + sort. */
    private void refresh() {
        if (list == null) return;
        List<VideoEntry> all = VideoCatalog.entries();

        for (PlatformToggleButton b : platformButtons) b.refreshTooltip();
        starButton.refreshTooltip();
        sortButton.setMessage(Component.translatable("gui.dungeontrain.videos.sort",
                Component.translatable("gui.dungeontrain.videos.sort." + sort.key())));
        sortButton.setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.videos.sort.tooltip")));

        boolean loaded = VideoCatalog.state() == VideoCatalog.State.LOADED;
        for (PlatformToggleButton b : platformButtons) b.active = loaded;
        starButton.active = loaded;
        sortButton.active = loaded;
        uploaderBox.setEditable(loaded);
        retryButton.visible = VideoCatalog.state() == VideoCatalog.State.FAILED;

        dropdown.setRows(VideoQuery.channels(all, filter.channelQuery()));
        list.setRows(VideoQuery.apply(all, filter, sort));
    }

    // ---- dropdown plumbing ----------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // The suggestion list gets first refusal — it is drawn over the video list, so a click on it
        // must not fall through to the row underneath.
        if (dropdown != null && dropdown.isMouseOver(mouseX, mouseY)) {
            dropdown.mouseClicked(mouseX, mouseY, button);
            return true;   // whatever the button, nothing under the panel hears this
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (dropdown != null && dropdown.isMouseOver(mouseX, mouseY)) {
            dropdown.mouseScrolled(mouseX, mouseY, scrollY);
            return true;   // a full-height panel with nothing more to scroll still swallows the wheel
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        // The press over the panel was swallowed above; the matching release must not start a
        // scrollbar drag or a click on whatever is under it either.
        if (dropdown != null && dropdown.isMouseOver(mouseX, mouseY)) return true;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Escape with the box focused just closes the suggestions; a second Escape leaves the page.
        if (keyCode == 256 && dropdown != null && dropdown.isOpen()) {
            uploaderBox.setFocused(false);
            setFocused(null);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---- opening a video -----------------------------------------------------------

    /** Vanilla's confirm screen, then the browser; back here either way. */
    private void open(VideoEntry v) {
        UiAnalytics.click(UiAnalytics.SURFACE_VIDEOS, UiAnalytics.TARGET_VIDEO_OPEN);
        String url = v.url();
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            UiAnalytics.confirm(UiAnalytics.SURFACE_VIDEOS, UiAnalytics.TARGET_VIDEO_OPEN, yes);
            if (yes) {
                Util.getPlatform().openUri(URI.create(url));
            }
            Minecraft.getInstance().setScreen(this);
        }, url, true));
    }

    private void addChannelIcon(Button icon, String key) {
        icon.setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.videos.channels." + key)));
        addRenderableWidget(icon);
    }

    /** One of Brennan's channels: vanilla confirm, browser, back here. The URL is read at click time. */
    private void openChannel(String target, String url) {
        UiAnalytics.click(UiAnalytics.SURFACE_VIDEOS, target);
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            UiAnalytics.confirm(UiAnalytics.SURFACE_VIDEOS, target, yes);
            if (yes) {
                Util.getPlatform().openUri(URI.create(url));
            }
            Minecraft.getInstance().setScreen(this);
        }, url, true));
    }

    /** The row's ⚑: report this video. The flag screen talks to the relay and comes back here. */
    private void flag(VideoEntry v) {
        UiAnalytics.click(UiAnalytics.SURFACE_VIDEOS, UiAnalytics.TARGET_VIDEO_FLAG);
        Minecraft.getInstance().setScreen(new VideoFlagScreen(this, v));
    }

    // ---- render --------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);

        Component state = switch (VideoCatalog.state()) {
            case IDLE, LOADING -> Component.translatable("gui.dungeontrain.videos.state.loading");
            case FAILED -> Component.translatable("gui.dungeontrain.videos.state.failed");
            case LOADED -> list.rowCount() == 0
                    ? Component.translatable("gui.dungeontrain.videos.state.empty") : null;
        };
        if (state != null) {
            int colour = VideoCatalog.state() == VideoCatalog.State.FAILED ? ERROR_COLOUR : SUB_COLOUR;
            g.drawCenteredString(this.font, state, this.width / 2,
                    list.getY() + list.getHeight() / 2 - this.font.lineHeight, colour);
        }

        // Row count on the title line, right-aligned, out of the toolbar's way.
        if (VideoCatalog.state() == VideoCatalog.State.LOADED) {
            Component count = Component.translatable("gui.dungeontrain.videos.count",
                    list.rowCount(), VideoCatalog.entries().size());
            g.drawString(this.font, count, this.width - MARGIN - this.font.width(count), 14, SUB_COLOUR);
        }

        // The suggestion list is open exactly while the box has focus; drawn last so it sits over
        // the video list.
        dropdown.setOpen(uploaderBox.isFocused());
        dropdown.render(g, mouseX, mouseY);

        // The box's tooltip goes ABOVE the box — vanilla's positioner hangs it under the cursor,
        // which is exactly where the suggestion panel is.
        if (uploaderBox.isHovered() && !dropdown.isMouseOver(mouseX, mouseY)) {
            g.renderTooltip(this.font,
                    this.font.split(Component.translatable("gui.dungeontrain.videos.filter.uploader.tooltip"), 220),
                    new AboveWidgetPositioner(uploaderBox.getX(), uploaderBox.getY(), uploaderBox.getWidth()),
                    mouseX, mouseY);
        }
    }

    /** Puts a tooltip directly above a widget, left-aligned to it and kept on screen. */
    private record AboveWidgetPositioner(int widgetX, int widgetY, int widgetW) implements ClientTooltipPositioner {
        /** Vanilla pads the tooltip background 3px around the text, plus a little air over the widget. */
        private static final int PAD = 3;
        private static final int AIR = 4;

        @Override
        public Vector2ic positionTooltip(int screenWidth, int screenHeight, int mouseX, int mouseY,
                                         int tooltipWidth, int tooltipHeight) {
            int x = Mth.clamp(widgetX, PAD, Math.max(PAD, screenWidth - tooltipWidth - PAD));
            int y = widgetY - tooltipHeight - PAD - AIR;
            if (y < PAD) y = PAD;
            return new Vector2i(x, y);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
