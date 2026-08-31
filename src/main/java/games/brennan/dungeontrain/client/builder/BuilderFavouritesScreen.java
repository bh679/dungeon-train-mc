package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuilderRelayDownload;
import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.net.BuilderFavouritePacket;
import games.brennan.dungeontrain.net.BuilderFavouritesPacket;
import games.brennan.dungeontrain.net.BuilderFavouritesRequestPacket;
import games.brennan.dungeontrain.net.BuilderProfileDownloadPacket;
import games.brennan.dungeontrain.net.BuilderProfileDownloadResultPacket;
import games.brennan.dungeontrain.net.BuilderProfilePacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriagePartKind;
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
 * <p>Two sections. The builds come first and get the grid, because they are what a favourite usually
 * is. The starred BUILDERS are rows underneath, and are dev-build only for the plain reason that
 * starring one is: the creator search is the only way to reach a builder, and nothing on a release
 * build opens it.</p>
 *
 * <p><b>Load into editor</b> is the point of the screen — it is the same
 * {@link BuilderProfileDownloadPacket} path My Builds uses, so a favourite of somebody else's work can
 * be pulled into this install and opened like anything else.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderFavouritesScreen extends Screen {

    private static final int TITLE_TOP = 14;
    private static final int CONTROL_TOP = 30;
    private static final int CONTROL_ROW_H = 20;
    private static final int BACK_BUTTON_WIDTH = 200;
    private static final int BACK_BUTTON_BOTTOM_MARGIN = 28;
    private static final int STATUS_GAP = 14;
    private static final int SCROLL_STEP = 24;
    private static final int NOTE_COLOUR = 0xA0A0A0;
    private static final int ACTION_GAP = 4;
    private static final int BUILDER_ROW_H = 18;
    private static final int BUILDER_ROW_GAP = 2;
    private static final int BUILDER_STAR_W = 20;
    private static final int BUILDER_ROW_W = 180;
    /** How much of the screen the starred-builder rows may take before the grid is squeezed. */
    private static final int BUILDER_SECTION_MAX_ROWS = 4;

    private static final float MAX_FRAME_SECONDS = 0.1F;

    private final Screen lastScreen;

    private List<BuilderProfilePacket.Entry> builds = List.of();
    private List<BuilderFavouritesPacket.Builder> builders = List.of();
    private BuilderProfilePacket.Status status = null;

    private BuilderTemplateGridLayout grid;
    private int scrollY;
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

    /** How many starred-builder rows are actually drawn — none at all on a release build. */
    private int builderRows() {
        if (!DungeonTrain.isDevBuild()) return 0;
        return Math.min(builders.size(), BUILDER_SECTION_MAX_ROWS);
    }

    private void rebuild() {
        clearWidgets();

        // The starred builders sit above the grid: there are few of them, they are fixed in number,
        // and putting them under a scrolling wall of pictures would make them the part you have to go
        // looking for — which is the opposite of what starring one was for.
        int rows = builderRows();
        int y = CONTROL_TOP;
        for (int i = 0; i < rows; i++) {
            BuilderFavouritesPacket.Builder builder = builders.get(i);
            int rowX = this.width / 2 - (BUILDER_ROW_W + ACTION_GAP + BUILDER_STAR_W) / 2;
            addRenderableWidget(Button.builder(
                            Component.translatable("gui.dungeontrain.builder.creators.row",
                                    builder.name(), builder.builds()),
                            b -> viewBuilder(builder))
                    .bounds(rowX, y, BUILDER_ROW_W, BUILDER_ROW_H)
                    .build());
            // Every builder listed here is starred by definition, so this button only ever un-stars.
            addRenderableWidget(Button.builder(Component.literal("★"),
                            b -> unstarBuilder(builder))
                    .bounds(rowX + BUILDER_ROW_W + ACTION_GAP, y, BUILDER_STAR_W, BUILDER_ROW_H)
                    .build());
            y += BUILDER_ROW_H + BUILDER_ROW_GAP;
        }

        int gridTop = rows > 0 ? y + ACTION_GAP : CONTROL_TOP;
        int gridBottom = this.height - BACK_BUTTON_BOTTOM_MARGIN - STATUS_GAP - 24;
        this.grid = BuilderTemplateGridLayout.of(this.width, gridTop, gridBottom, builds.size(),
                BuilderTilesPerRowButton.effectiveColumns(this.width));
        this.scrollY = grid.clampScroll(scrollY);

        int left = this.width / 2 - BACK_BUTTON_WIDTH / 2;

        this.downloadButton = Button.builder(
                        Component.translatable("gui.dungeontrain.builder.profile.load_into_editor"),
                        b -> downloadSelected())
                .bounds(left, gridBottom + 4, BACK_BUTTON_WIDTH, 20)
                .build();
        this.downloadButton.active = selectedBuild() != null;
        addRenderableWidget(this.downloadButton);

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(left, this.height - BACK_BUTTON_BOTTOM_MARGIN, BACK_BUTTON_WIDTH, 20)
                .build());
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

    /**
     * A tile's caption: the build's name, and whose it is.
     *
     * <p>The owner is the one thing this screen has to say that My Builds never does — there every
     * build has the same author, here the list is a mix and a name is the only way to tell one
     * player's cabin from another's.</p>
     */
    private Component labelFor(BuilderProfilePacket.Entry entry) {
        String name = entry.buildName() == null ? "" : entry.buildName();
        if (entry.ownerName() == null || entry.ownerName().isEmpty()) return Component.literal(name);
        return Component.translatable("gui.dungeontrain.builder.favourites.tile", name, entry.ownerName());
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
