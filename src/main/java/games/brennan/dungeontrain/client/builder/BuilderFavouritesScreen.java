package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuilderRelayDownload;
import games.brennan.dungeontrain.net.BuilderFavouritePacket;
import games.brennan.dungeontrain.net.BuilderFavouritesPacket;
import games.brennan.dungeontrain.net.BuilderFavouritesRequestPacket;
import games.brennan.dungeontrain.net.BuilderProfileDownloadPacket;
import games.brennan.dungeontrain.net.BuilderProfileDownloadResultPacket;
import games.brennan.dungeontrain.net.BuilderProfilePacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * <b>Favourites</b> — everything this player has starred, across every builder.
 *
 * <p>The screen a star on somebody else's build is FOR. {@link BuilderProfileScreen}'s favourite chip
 * narrows one profile, which is the useful thing when the build is your own; a star set on a build you
 * reached through the creator search would otherwise be findable only by searching for that builder
 * again, which is the trip the star was meant to save.</p>
 *
 * <p>Because this list spans owners, each tile is captioned with whose build it is — the one thing My
 * Builds never has to say, where every row has the same author.</p>
 *
 * <p>Two TABS rather than two stacked sections. Builds and builders are different things to be
 * looking for and you want one of them at a time; stacking both meant the rows ate the top of the
 * screen whether or not you cared, and pushed the grid's last row out of its own scissor so those
 * tiles lost their captions entirely.</p>
 *
 * <p>The builders tab is dev-build only, for the plain reason that starring a builder is: the creator
 * search is the only way to reach one and nothing on a release build opens it, so a player's builder
 * list is always empty — and a tab that can only ever be empty is worse than no tab. There, this is
 * one grid at full height, which is what it was before the tabs.</p>
 *
 * <p><b>Load into editor</b> is the point of the screen — it is the same
 * {@link BuilderProfileDownloadPacket} path My Builds uses, so a favourite of somebody else's work can
 * be pulled into this install and opened like anything else.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderFavouritesScreen extends Screen {

    private static final int TITLE_TOP = 14;
    private static final int CONTROL_TOP = 30;
    private static final int BACK_BUTTON_WIDTH = 200;
    private static final int BACK_BUTTON_BOTTOM_MARGIN = 28;
    private static final int STATUS_GAP = 14;
    private static final int SCROLL_STEP = 24;
    private static final int NOTE_COLOUR = 0xA0A0A0;
    private static final int ACTION_GAP = 4;
    private static final int BUILDER_ROW_H = 20;
    private static final int BUILDER_ROW_GAP = 2;
    private static final int BUILDER_STAR_W = 20;
    private static final int BUILDER_ROW_W = 200;

    /** The two tabs, side by side under the title. */
    private static final int TAB_W = 98;
    private static final int TAB_H = 20;

    /**
     * Which tab the screen opens on.
     *
     * <p>Static and session-scoped, the way {@link BuilderProfileScreen} keeps its filter chips:
     * coming back to this screen returns you to the list you were reading, and a fresh launch starts
     * on the builds, which are what a favourite usually is.</p>
     */
    private static boolean showingBuilders = false;

    private static final float MAX_FRAME_SECONDS = 0.1F;

    private final Screen lastScreen;

    private List<BuilderProfilePacket.Entry> builds = List.of();
    private List<BuilderFavouritesPacket.Builder> builders = List.of();
    private BuilderProfilePacket.Status status = null;

    private BuilderTemplateGridLayout grid;
    private int scrollY;
    /** The builders list's own scroll, in rows. Separate from the grid's — two lists, two positions. */
    private int builderScroll;
    private int selected = -1;
    private Button downloadButton;
    private Component downloadNote;

    private final BuilderTileSpin spin = new BuilderTileSpin();
    private long lastFrameNanos;

    public BuilderFavouritesScreen(Screen lastScreen) {
        super(Component.translatable("gui.dungeontrain.builder.favourites.title"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        // The cached list is what makes a reopened screen instant; it is always re-asked anyway,
        // because a star set in My Builds since the last visit is exactly what this screen is for.
        BuilderFavouritesPacket latest = BuilderProfileState.favourites();
        if (latest != null) {
            this.builds = latest.builds();
            this.builders = latest.builders();
            this.status = latest.status();
        }
        BuilderProfileState.listenForFavourites(this::onFavourites);
        BuilderProfileState.listenForDownloads(this::onDownload);
        DungeonTrainNet.sendToServer(new BuilderFavouritesRequestPacket(BuilderProfileState.live()));

        this.spin.clear();
        this.lastFrameNanos = 0L;
        rebuild();
    }

    /**
     * A list arrived. The selection is kept on the same BUILD by relay id rather than by index, for
     * the reason {@link BuilderProfileScreen} does the same: an index means something different in
     * every list, so keeping the number would move the selection onto a build nobody chose.
     */
    private void onFavourites(BuilderFavouritesPacket packet) {
        int selectedId = selectedBuild() == null ? -1 : selectedBuild().relayId();
        this.downloadNote = null;
        this.builds = packet.builds();
        this.builders = packet.builders();
        this.status = packet.status();
        this.selected = -1;
        for (int i = 0; i < builds.size(); i++) {
            if (builds.get(i).relayId() == selectedId) {
                this.selected = i;
                break;
            }
        }
        rebuild();
    }

    /**
     * Whether the builders tab exists at all. See the class doc: on a release build a player has no
     * way to star a builder, so the tab could only ever be empty.
     */
    private boolean buildersTabAvailable() {
        return DungeonTrain.isDevBuild();
    }

    /** Which list is on screen. Asked once per rebuild rather than re-derived by each consumer. */
    private boolean onBuilders() {
        return showingBuilders && buildersTabAvailable();
    }

    /** How far down the content starts — under the tabs when there are any. */
    private int contentTop() {
        return buildersTabAvailable() ? CONTROL_TOP + TAB_H + ACTION_GAP : CONTROL_TOP;
    }

    /** The bottom of the scrolling area, above the buttons. */
    private int contentBottom() {
        return this.height - BACK_BUTTON_BOTTOM_MARGIN - STATUS_GAP - 24;
    }

    private void rebuild() {
        clearWidgets();

        int left = this.width / 2 - BACK_BUTTON_WIDTH / 2;
        if (buildersTabAvailable()) addTabs();

        // The grid is laid out on BOTH tabs. It costs nothing when it is not drawn, and it means the
        // scroll clamp and every geometry call downstream can rely on it being there rather than each
        // having to ask which tab is showing.
        this.grid = BuilderTemplateGridLayout.of(this.width, contentTop(), contentBottom(),
                builds.size(), BuilderTilesPerRowButton.effectiveColumns(this.width));
        this.scrollY = grid.clampScroll(scrollY);

        if (onBuilders()) {
            addBuilderRows();
        } else {
            // Only the builds tab has anything to load — the button would have nothing to act on
            // beside a list of people.
            this.downloadButton = Button.builder(
                            Component.translatable("gui.dungeontrain.builder.profile.load_into_editor"),
                            b -> downloadSelected())
                    .bounds(left, contentBottom() + 4, BACK_BUTTON_WIDTH, 20)
                    .build();
            this.downloadButton.active = selectedBuild() != null;
            addRenderableWidget(this.downloadButton);
        }

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(left, this.height - BACK_BUTTON_BOTTOM_MARGIN, BACK_BUTTON_WIDTH, 20)
                .build());
    }

    /**
     * The two tabs. The one you are on is the INACTIVE button — a tab you are already looking at is
     * not something to press — which says so with vanilla's own styling and needs no new widget.
     */
    private void addTabs() {
        int tabsX = this.width / 2 - (TAB_W * 2 + ACTION_GAP) / 2;
        Button buildsTab = Button.builder(
                        Component.translatable("gui.dungeontrain.builder.favourites.tab.builds",
                                builds.size()),
                        b -> switchTab(false))
                .bounds(tabsX, CONTROL_TOP, TAB_W, TAB_H)
                .build();
        buildsTab.active = onBuilders();
        addRenderableWidget(buildsTab);

        Button buildersTab = Button.builder(
                        Component.translatable("gui.dungeontrain.builder.favourites.tab.builders",
                                builders.size()),
                        b -> switchTab(true))
                .bounds(tabsX + TAB_W + ACTION_GAP, CONTROL_TOP, TAB_W, TAB_H)
                .build();
        buildersTab.active = !onBuilders();
        addRenderableWidget(buildersTab);
    }

    /** Move to the other list. The scroll goes with it — an offset from one list means nothing in the other. */
    private void switchTab(boolean builders) {
        if (showingBuilders == builders) return;
        showingBuilders = builders;
        this.scrollY = 0;
        this.builderScroll = 0;
        this.downloadNote = null;
        rebuild();
    }

    /**
     * The starred builders, one row each: their name, and the star that takes them off the list.
     *
     * <p>Uncapped, unlike the four this screen used to squeeze in above the grid. A list that quietly
     * stopped at four would hide a builder the player had deliberately starred, which is
     * indistinguishable from the star not having worked. It scrolls instead.</p>
     */
    private void addBuilderRows() {
        int rowX = this.width / 2 - (BUILDER_ROW_W + ACTION_GAP + BUILDER_STAR_W) / 2;
        int top = contentTop();
        int visible = visibleBuilderRows();
        this.builderScroll = Math.max(0, Math.min(builderScroll, Math.max(0, builders.size() - visible)));
        for (int i = 0; i < visible && builderScroll + i < builders.size(); i++) {
            BuilderFavouritesPacket.Builder builder = builders.get(builderScroll + i);
            int y = top + i * (BUILDER_ROW_H + BUILDER_ROW_GAP);
            addRenderableWidget(Button.builder(
                            Component.translatable("gui.dungeontrain.builder.creators.row",
                                    builder.name(), builder.builds()),
                            b -> viewBuilder(builder))
                    .bounds(rowX, y, BUILDER_ROW_W, BUILDER_ROW_H)
                    .build());
        }
        // The stars are NOT buttons: they are drawn in render() from the same sprite the tiles use, so
        // the two read as one control. A Button would have to carry the star as a text glyph, which is
        // what the previous version did and what made it an unreadable blob in the game's own font.
    }

    /** How many builder rows fit between the tabs and the buttons, at least one. */
    private int visibleBuilderRows() {
        int available = contentBottom() - contentTop();
        return Math.max(1, available / (BUILDER_ROW_H + BUILDER_ROW_GAP));
    }

    /** The star's x for a visible builder row — the renderer and the hit test share it. */
    private int builderStarX() {
        return this.width / 2 - (BUILDER_ROW_W + ACTION_GAP + BUILDER_STAR_W) / 2
                + BUILDER_ROW_W + ACTION_GAP;
    }

    private int builderStarY(int visibleIndex) {
        return contentTop() + visibleIndex * (BUILDER_ROW_H + BUILDER_ROW_GAP);
    }

    private BuilderProfilePacket.Entry selectedBuild() {
        return selected >= 0 && selected < builds.size() ? builds.get(selected) : null;
    }

    /** Open a starred builder's profile — the same screen the creator search opens. */
    private void viewBuilder(BuilderFavouritesPacket.Builder builder) {
        BuilderProfileState.setViewed(builder.uuid(), builder.name());
        this.minecraft.setScreen(new BuilderProfileScreen(lastScreen, builder.uuid(), builder.name()));
    }

    /**
     * Take the star off a builder. Optimistic, like the tile stars: the row goes now and the packet
     * follows, because a row that lingered until the relay answered would read as a failed click.
     */
    private void unstarBuilder(BuilderFavouritesPacket.Builder builder) {
        DungeonTrainNet.sendToServer(
                BuilderFavouritePacket.forBuilder(builder.uuid(), false, BuilderProfileState.live()));
        this.builders = builders.stream().filter(b -> !b.uuid().equals(builder.uuid())).toList();
        rebuild();
    }

    /**
     * Take the star off a build.
     *
     * <p>Unlike the profile screen's star this one only ever removes: every build on this screen is
     * starred, so the row leaves the list. It is dropped locally rather than waiting for the reply,
     * for the same reason — and the cached list is invalidated so the next visit re-reads the truth
     * rather than trusting this screen's edit of it.</p>
     */
    private void unstarBuild(BuilderProfilePacket.Entry entry) {
        DungeonTrainNet.sendToServer(
                BuilderFavouritePacket.forBuild(entry.relayId(), false, BuilderProfileState.live()));
        BuilderProfileState.noteFavourite(entry.relayId(), false);
        this.builds = builds.stream().filter(b -> b.relayId() != entry.relayId()).toList();
        this.selected = -1;
        this.downloadNote = null;
        rebuild();
    }

    /** Ask the server for this build's blocks — the same path My Builds' Load into editor takes. */
    private void downloadSelected() {
        BuilderProfilePacket.Entry entry = selectedBuild();
        if (entry == null) return;
        // Addressed to whoever BUILT it, which on this screen is routinely not the player: the relay
        // authorises a fetch by owner uuid, and naming ourselves would be asking for a build we do
        // not own under a name that owns nothing.
        DungeonTrainNet.sendToServer(new BuilderProfileDownloadPacket(entry.relayId(),
                entry.ownerUuid(), BuilderProfileState.live()));
        this.downloadButton.active = false;
        this.downloadNote = Component.translatable("gui.dungeontrain.builder.profile.downloading");
    }

    private void onDownload(BuilderProfileDownloadResultPacket packet) {
        this.downloadNote = Component.translatable(BuilderProfileScreen.noteKeyFor(packet.outcome()));
        if (this.downloadButton != null) this.downloadButton.active = selectedBuild() != null;
        if (packet.outcome() != BuilderRelayDownload.Outcome.INSTALLED) return;
        BuilderPhotoPaths.Kind kind = BuilderPhotoPaths.Kind.fromId(packet.kindId()).orElse(null);
        if (kind == null) return;
        // Left in this install's library rather than opened here. My Builds opens a download because
        // it is the screen you were already working from; Favourites is a place you came to look, and
        // yanking the player into a build they had only starred would be a surprise.
        this.downloadNote = Component.translatable("gui.dungeontrain.builder.favourites.installed");
    }

    // ---- input ----

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Which list is on screen decides what a click can hit, asked ONCE here rather than by each
        // branch below: the tab governs both what is drawn and what an index means, and the two
        // disagreeing is how a click lands on something nobody pointed at.
        if (button == 0 && onBuilders()) {
            int visible = visibleBuilderRows();
            for (int i = 0; i < visible && builderScroll + i < builders.size(); i++) {
                int sx = builderStarX();
                int sy = builderStarY(i);
                if (mouseX >= sx && mouseX < sx + BUILDER_STAR_W
                        && mouseY >= sy && mouseY < sy + BUILDER_ROW_H) {
                    unstarBuilder(builders.get(builderScroll + i));
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0 && !builds.isEmpty()) {
            for (int i = 0; i < builds.size(); i++) {
                if (grid.isVisible(i, scrollY) && grid.isOverStar(i, mouseX, mouseY, scrollY)) {
                    unstarBuild(builds.get(i));
                    return true;
                }
            }
            int index = grid.indexAt(mouseX, mouseY, scrollY, builds.size());
            if (index >= 0) {
                this.selected = index;
                this.downloadNote = null;
                rebuild();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (onBuilders()) {
            int overflow = builders.size() - visibleBuilderRows();
            if (overflow <= 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
            this.builderScroll = Math.max(0, Math.min(overflow,
                    this.builderScroll - (int) Math.signum(scrollY)));
            rebuild();
            return true;
        }
        if (grid != null && grid.maxScroll() > 0) {
            this.scrollY = grid.clampScroll(this.scrollY - (int) (scrollY * SCROLL_STEP));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ---- render ----

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, TITLE_TOP, 0xFFFFFF);

        if (onBuilders()) {
            // The rows themselves are widgets, already drawn by super.render(); only the stars are
            // ours, for the reason addBuilderRows explains.
            int visible = visibleBuilderRows();
            for (int i = 0; i < visible && builderScroll + i < builders.size(); i++) {
                int sx = builderStarX();
                int sy = builderStarY(i);
                boolean over = mouseX >= sx && mouseX < sx + BUILDER_STAR_W
                        && mouseY >= sy && mouseY < sy + BUILDER_ROW_H;
                // Always filled: every builder on this list is starred, and the star is how you
                // take one off it.
                BuilderTemplateTile.renderStar(g, sx, sy, BUILDER_STAR_W, true, over);
            }
            Component buildersNote = buildersNote();
            if (buildersNote != null) {
                g.drawCenteredString(this.font, buildersNote, this.width / 2,
                        this.height - BACK_BUTTON_BOTTOM_MARGIN - STATUS_GAP + 2, NOTE_COLOUR);
            }
            return;
        }

        if (!builds.isEmpty()) {
            float seconds = frameSeconds();
            BuilderTileMeshCache.beginFrame();
            g.enableScissor(0, grid.topY(), this.width, grid.bottomY());
            for (int i = 0; i < builds.size(); i++) {
                if (!grid.isVisible(i, scrollY)) continue;
                BuilderProfilePacket.Entry entry = builds.get(i);
                int x = grid.xFor(i);
                int y = grid.yFor(i, scrollY);
                boolean hovered = mouseX >= x && mouseX < x + grid.cellWidth()
                        && mouseY >= y && mouseY < y + grid.cellHeight()
                        && mouseY >= grid.topY() && mouseY < grid.bottomY();
                BuilderTemplateTile.render(g, null, false,
                        BuilderProfileScreen.photoKindOf(entry), entry.buildName(),
                        BuilderProfileScreen.partKindOf(entry), BuilderProfileScreen.trackKindOf(entry),
                        labelFor(entry),
                        x, y, grid.cellWidth(), grid.cellHeight(), hovered || i == selected, true,
                        spin.advance(String.valueOf(entry.relayId()), hovered, seconds),
                        null, grid.badgeSize());
                // Always filled: everything here is starred, and the star is how you take it off.
                BuilderTemplateTile.renderStar(g, grid.starX(i), grid.starY(i, scrollY),
                        grid.starSize(), true, grid.isOverStar(i, mouseX, mouseY, scrollY));
            }
            g.disableScissor();
        }

        Component note = statusNote();
        if (note != null) {
            g.drawCenteredString(this.font, note, this.width / 2,
                    this.height - BACK_BUTTON_BOTTOM_MARGIN - STATUS_GAP + 2, NOTE_COLOUR);
        }
    }

    /** The line under the builders list — the empty case is the only one it has to answer. */
    private Component buildersNote() {
        if (status == null) return Component.translatable("gui.dungeontrain.builder.profile.loading");
        return switch (status) {
            case UNAVAILABLE -> Component.translatable("gui.dungeontrain.builder.profile.unavailable");
            case DISABLED -> Component.translatable("gui.dungeontrain.builder.profile.disabled");
            case NO_CONSENT -> Component.translatable("gui.dungeontrain.builder.profile.no_consent");
            case CONSENT_PENDING -> Component.translatable("gui.dungeontrain.builder.profile.consent_pending");
            case OK -> builders.isEmpty()
                    ? Component.translatable("gui.dungeontrain.builder.favourites.empty_builders")
                    : null;
        };
    }

    /**
     * A tile's caption: the build's name, and whose it is.
     *
     * <p>The owner is the one thing this screen has to say that My Builds never does — there every
     * build has the same author, here the list is a mix and a name is the only way to tell one
     * player's cabin from another's.</p>
     */
    private Component labelFor(BuilderProfilePacket.Entry entry) {
        String name = entry.buildName() == null ? "" : entry.buildName();
        if (entry.ownerName() == null || entry.ownerName().isEmpty() || isMine(entry)) {
            return Component.literal(name);
        }
        return Component.translatable("gui.dungeontrain.builder.favourites.tile", name, entry.ownerName());
    }

    /**
     * Whether this build is the player's own.
     *
     * <p>Used to leave the owner OFF its caption. "by Dev" on your own build is noise, and it is
     * noise that costs width — the caption is cut to fit its cell, so an owner nobody needed pushes
     * the build's actual name into an ellipsis. Saying it only for somebody else's makes the foreign
     * ones the ones that stand out, which is the thing this screen is actually carrying.</p>
     */
    private boolean isMine(BuilderProfilePacket.Entry entry) {
        if (this.minecraft == null || this.minecraft.player == null) return false;
        String uuid = entry.ownerUuid();
        return uuid != null && !uuid.isEmpty()
                && uuid.equalsIgnoreCase(this.minecraft.player.getUUID().toString());
    }

    /**
     * The line under the grid.
     *
     * <p>"You have starred nothing", "we could not ask" and "you declined network consent" are three
     * different answers to the same empty screen, and only the first is about the player having done
     * nothing yet.</p>
     */
    private Component statusNote() {
        if (downloadNote != null) return downloadNote;
        if (status == null) return Component.translatable("gui.dungeontrain.builder.profile.loading");
        return switch (status) {
            case UNAVAILABLE -> Component.translatable("gui.dungeontrain.builder.profile.unavailable");
            case DISABLED -> Component.translatable("gui.dungeontrain.builder.profile.disabled");
            case NO_CONSENT -> Component.translatable("gui.dungeontrain.builder.profile.no_consent");
            case CONSENT_PENDING -> Component.translatable("gui.dungeontrain.builder.profile.consent_pending");
            case OK -> builds.isEmpty() && builders.isEmpty()
                    ? Component.translatable("gui.dungeontrain.builder.favourites.empty")
                    : null;
        };
    }

    /** Seconds since the last frame, clamped so a stalled one doesn't fling the tile spin round. */
    private float frameSeconds() {
        long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            return 0F;
        }
        float seconds = (now - lastFrameNanos) / 1_000_000_000F;
        lastFrameNanos = now;
        return Math.min(seconds, MAX_FRAME_SECONDS);
    }

    @Override
    public void removed() {
        super.removed();
        BuilderProfileState.listenForFavourites(null);
        BuilderProfileState.listenForDownloads(null);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(lastScreen);
    }
}
