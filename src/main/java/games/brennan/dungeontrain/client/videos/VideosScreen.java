package games.brennan.dungeontrain.client.videos;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.net.URI;
import java.util.List;

/**
 * The <b>Videos</b> page, opened from the title screen's icon column: every video about Dungeon
 * Train the relay has saved — YouTube, Bilibili, Twitch, Instagram — filterable by platform,
 * uploader and the developer's picks, sortable by views, recency or those picks. A row opens the
 * video in the browser through vanilla's link-confirm screen.
 *
 * <p>The filters are <b>cycle buttons</b> rather than dropdowns, like the Shaders page's sort: they
 * say which state is active, and Shift-click steps backwards so a thirty-name uploader list is not
 * a thirty-click round trip. Filter and sort state survive a resize (fields on the screen), and the
 * catalogue itself survives the session ({@link VideoCatalog}).</p>
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
    private static final int BOTTOM_ROW_H = 20;
    private static final int SUB_COLOUR = 0xFF9A9A9A;
    private static final int ERROR_COLOUR = 0xFFCF5C5C;

    private final Screen parent;

    private VideoQuery.Filter filter = VideoQuery.Filter.ALL;
    private VideoQuery.Sort sort = VideoQuery.Sort.VIEWS;

    private Button platformButton;
    private Button uploaderButton;
    private Button devFavButton;
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

        int rowW = this.width - 2 * MARGIN;
        int thirdW = (rowW - 2 * GAP) / 3;

        // Row 1 — the three filters. Each says its active state; Shift-click cycles backwards.
        platformButton = addRenderableWidget(new DarkTintedButton(MARGIN, TOP, thirdW, BUTTON_H,
                CommonComponents.EMPTY, b -> cyclePlatform(hasShiftDown() ? -1 : 1)));
        uploaderButton = addRenderableWidget(new DarkTintedButton(MARGIN + thirdW + GAP, TOP, thirdW, BUTTON_H,
                CommonComponents.EMPTY, b -> cycleUploader(hasShiftDown() ? -1 : 1)));
        devFavButton = addRenderableWidget(new DarkTintedButton(MARGIN + 2 * (thirdW + GAP), TOP,
                rowW - 2 * (thirdW + GAP), BUTTON_H, CommonComponents.EMPTY, b -> toggleDevFav()));

        // Row 2 — sort, on the left; the row count is drawn to its right in render().
        int sortY = TOP + BUTTON_H + GAP;
        sortButton = addRenderableWidget(new DarkTintedButton(MARGIN, sortY, thirdW, BUTTON_H,
                CommonComponents.EMPTY, b -> cycleSort()));

        int listTop = sortY + BUTTON_H + GAP;
        int listBottom = this.height - MARGIN - BOTTOM_ROW_H - GAP;
        list = addRenderableWidget(new VideoList(this.font, MARGIN, listTop, rowW, listBottom - listTop, this::open));

        // Retry sits in the middle of the (empty) list and only shows when the fetch failed.
        retryButton = addRenderableWidget(new DarkTintedButton(this.width / 2 - 50,
                (listTop + listBottom) / 2 + this.font.lineHeight, 100, BUTTON_H,
                Component.translatable("gui.dungeontrain.videos.retry"), b -> {
                    VideoCatalog.retry();
                    refresh();
                }));

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(this.width / 2 - 100, this.height - MARGIN - BOTTOM_ROW_H, 200, BOTTOM_ROW_H)
                .build());

        refresh();
    }

    /** Called on the render thread when the catalogue loads or fails. */
    public void onCatalogChanged() {
        // A filter chosen while loading may name nothing in the real data; drop back to "all".
        List<VideoEntry> all = VideoCatalog.entries();
        if (filter.channel() != null && !VideoQuery.channels(all).contains(filter.channel())) {
            filter = filter.withChannel(null);
        }
        if (filter.platform() != null && !VideoQuery.platforms(all).contains(filter.platform())) {
            filter = filter.withPlatform(null);
        }
        refresh();
    }

    // ---- filter / sort controls -------------------------------------------------

    private void cyclePlatform(int step) {
        List<VideoEntry.Platform> options = VideoQuery.platforms(VideoCatalog.entries());
        filter = filter.withPlatform(cycle(options, filter.platform(), step));
        refresh();
    }

    private void cycleUploader(int step) {
        List<String> options = VideoQuery.channels(VideoCatalog.entries());
        filter = filter.withChannel(cycle(options, filter.channel(), step));
        refresh();
    }

    /**
     * Step through {@code null} (all) → options… → {@code null}. {@code step} is +1 or -1. An empty
     * option list stays on "all" — the button still says something, it just cannot move.
     */
    private static <T> T cycle(List<T> options, T current, int step) {
        if (options.isEmpty()) return null;
        int n = options.size() + 1;                  // +1 for the "all" state at index 0
        int idx = current == null ? 0 : options.indexOf(current) + 1;
        int next = ((idx + step) % n + n) % n;
        return next == 0 ? null : options.get(next - 1);
    }

    private void toggleDevFav() {
        filter = filter.withDevFavOnly(!filter.devFavOnly());
        refresh();
    }

    private void cycleSort() {
        sort = sort.next();
        refresh();
    }

    /** Relabel every control and rebuild the list from the current catalogue + filter + sort. */
    private void refresh() {
        if (list == null) return;
        List<VideoEntry> all = VideoCatalog.entries();

        platformButton.setMessage(Component.translatable("gui.dungeontrain.videos.filter.platform",
                filter.platform() == null
                        ? Component.translatable("gui.dungeontrain.videos.filter.all")
                        : Component.translatable("gui.dungeontrain.videos.platform." + filter.platform().key())));
        uploaderButton.setMessage(Component.translatable("gui.dungeontrain.videos.filter.uploader",
                filter.channel() == null
                        ? Component.translatable("gui.dungeontrain.videos.filter.all")
                        : Component.literal(filter.channel())));
        devFavButton.setMessage(Component.translatable("gui.dungeontrain.videos.filter.dev_faves",
                Component.translatable(filter.devFavOnly() ? "options.on" : "options.off")));
        sortButton.setMessage(Component.translatable("gui.dungeontrain.videos.sort",
                Component.translatable("gui.dungeontrain.videos.sort." + sort.key())));

        boolean loaded = VideoCatalog.state() == VideoCatalog.State.LOADED;
        platformButton.active = loaded && !VideoQuery.platforms(all).isEmpty();
        uploaderButton.active = loaded && !VideoQuery.channels(all).isEmpty();
        devFavButton.active = loaded;
        sortButton.active = loaded;
        retryButton.visible = VideoCatalog.state() == VideoCatalog.State.FAILED;

        list.setRows(VideoQuery.apply(all, filter, sort));
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

    // ---- render --------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);

        // Row count beside the sort button: "12 of 87 videos" once loaded.
        if (VideoCatalog.state() == VideoCatalog.State.LOADED) {
            Component count = Component.translatable("gui.dungeontrain.videos.count",
                    list.rowCount(), VideoCatalog.entries().size());
            g.drawString(this.font, count, sortButton.getX() + sortButton.getWidth() + 2 * GAP,
                    sortButton.getY() + (BUTTON_H - this.font.lineHeight) / 2, SUB_COLOUR);
        }

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
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
