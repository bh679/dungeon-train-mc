package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderLabels;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.net.BuilderProfileActionPacket;
import games.brennan.dungeontrain.net.BuilderProfilePacket;
import games.brennan.dungeontrain.net.BuilderProfileRequestPacket;
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
 * <b>My Builds</b> — everything this player has uploaded to their relay profile, and the one action
 * that moves a build between the two states it can be in.
 *
 * <p>A save writes the template to disk and, when profiles are on, uploads it. That upload is private:
 * it follows the player between worlds and shows up here, and nowhere else. <b>Submit to the train</b>
 * is what makes a build part of the game for everybody — which is why it is a separate button on a
 * separate screen rather than something a save quietly does.</p>
 *
 * <p>The tiles are drawn from the LOCAL template of the same name, through the same
 * {@link BuilderTileMeshCache} the Open grid uses, so a build made on this machine shows its own
 * blocks. A build uploaded from another world has no local file and falls back to a flat tile —
 * downloading one back into the builder is the next piece of work, not this one.</p>
 *
 * <p>Only a whole carriage can be submitted ({@link BuilderRelayKinds#canJoinTheTrain}); every other
 * kind the builder authors is a piece of something rather than a thing a train slot can hold, so its
 * tile says where it lives and offers nothing to press.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderProfileScreen extends Screen {

    private static final int TITLE_TOP = 14;
    private static final int GRID_TOP_GAP = 26;
    private static final int BACK_BUTTON_WIDTH = 200;
    private static final int BACK_BUTTON_BOTTOM_MARGIN = 28;
    private static final int STATUS_GAP = 14;
    private static final int SCROLL_STEP = 24;
    private static final int NOTE_COLOUR = 0xA0A0A0;
    private static final int TILES_PER_ROW = 3;

    /** Longest timestep the tile spin will accept, so a stalled frame doesn't fling it round. */
    private static final float MAX_FRAME_SECONDS = 0.1F;

    private final Screen lastScreen;

    private List<BuilderProfilePacket.Entry> builds = List.of();
    private BuilderProfilePacket.Status status = null;
    private BuilderTemplateGridLayout grid;
    private int scrollY;
    private int selected = -1;
    private Button actionButton;

    private final BuilderTileSpin spin = new BuilderTileSpin();
    private long lastFrameNanos;

    public BuilderProfileScreen(Screen lastScreen) {
        super(Component.translatable("gui.dungeontrain.builder.profile.title"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        BuilderProfilePacket latest = BuilderProfileState.latest();
        if (latest != null) {
            this.builds = latest.builds();
            this.status = latest.status();
        }
        // Always re-ask on the way in. The cached list is what makes a reopened screen instant, but it
        // may predate a save, a publish, or a build somebody else's world just returned.
        BuilderProfileState.listen(this::onProfile);
        DungeonTrainNet.sendToServer(new BuilderProfileRequestPacket());

        this.spin.clear();
        this.lastFrameNanos = 0L;
        rebuild();
    }

    /** A profile arrived. Keep the selection on the same build where it survives the refresh. */
    private void onProfile(BuilderProfilePacket packet) {
        int selectedId = selectedBuild() == null ? -1 : selectedBuild().relayId();
        this.builds = packet.builds();
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

    private void rebuild() {
        clearWidgets();
        int gridTop = TITLE_TOP + this.font.lineHeight + GRID_TOP_GAP;
        int gridBottom = this.height - BACK_BUTTON_BOTTOM_MARGIN - STATUS_GAP - 24;
        this.grid = BuilderTemplateGridLayout.of(this.width, gridTop, gridBottom, builds.size(), TILES_PER_ROW);
        this.scrollY = grid.clampScroll(scrollY);

        this.actionButton = Button.builder(actionLabel(), b -> submitSelected())
                .bounds(this.width / 2 - BACK_BUTTON_WIDTH / 2, gridBottom + 4, BACK_BUTTON_WIDTH, 20)
                .build();
        this.actionButton.active = canActOnSelection();
        addRenderableWidget(this.actionButton);

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(this.width / 2 - BACK_BUTTON_WIDTH / 2,
                        this.height - BACK_BUTTON_BOTTOM_MARGIN, BACK_BUTTON_WIDTH, 20)
                .build());
    }

    private BuilderProfilePacket.Entry selectedBuild() {
        return selected >= 0 && selected < builds.size() ? builds.get(selected) : null;
    }

    /**
     * Whether the button can do anything: a build has to be selected, and it has to be the kind a
     * train can hold. Withdrawing is always allowed on a published build — the relay is what refuses,
     * when somebody else is out riding it.
     */
    private boolean canActOnSelection() {
        BuilderProfilePacket.Entry entry = selectedBuild();
        return entry != null && BuilderRelayKinds.canJoinTheTrain(entry.kind());
    }

    private Component actionLabel() {
        BuilderProfilePacket.Entry entry = selectedBuild();
        if (entry == null) return Component.translatable("gui.dungeontrain.builder.profile.submit");
        if (!BuilderRelayKinds.canJoinTheTrain(entry.kind())) {
            return Component.translatable("gui.dungeontrain.builder.profile.not_a_carriage");
        }
        return Component.translatable(entry.published()
                ? "gui.dungeontrain.builder.profile.withdraw"
                : "gui.dungeontrain.builder.profile.submit");
    }

    private void submitSelected() {
        BuilderProfilePacket.Entry entry = selectedBuild();
        if (entry == null || !BuilderRelayKinds.canJoinTheTrain(entry.kind())) return;
        DungeonTrainNet.sendToServer(new BuilderProfileActionPacket(entry.relayId(), !entry.published()));
        // The server re-reads the profile once the relay answers, which lands back on this screen
        // through onProfile — so nothing is assumed to have worked here.
        this.actionButton.active = false;
    }

    // ---- input ----

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !builds.isEmpty()) {
            int index = grid.indexAt(mouseX, mouseY, scrollY, builds.size());
            if (index >= 0) {
                this.selected = index;
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
                        photoKindOf(entry), entry.buildName(), partKindOf(entry), trackKindOf(entry),
                        labelFor(entry),
                        x, y, grid.cellWidth(), grid.cellHeight(), hovered || i == selected, true,
                        spin.advance(String.valueOf(entry.relayId()), hovered, seconds));
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
     * The caption under a tile: what the build is called, and where it currently lives.
     *
     * <p>The state is on every tile rather than only the selected one, because "which of my builds are
     * actually on the train" is the question this screen exists to answer at a glance.</p>
     */
    private Component labelFor(BuilderProfilePacket.Entry entry) {
        String name = entry.buildName().isEmpty()
                ? "#" + entry.relayId()
                : BuilderLabels.pretty(entry.buildName());
        Component where = Component.translatable(entry.published()
                ? "gui.dungeontrain.builder.profile.on_train"
                : "gui.dungeontrain.builder.profile.in_profile");
        return Component.literal(name + " · ").append(where);
    }

    /** Which local store to draw this build's tile from — the mirror of {@link BuilderRelayKinds#idOf}. */
    private static BuilderPhotoPaths.Kind photoKindOf(BuilderProfilePacket.Entry entry) {
        return switch (entry.kind()) {
            case BuilderRelayKinds.CARRIAGE_GROUP -> BuilderPhotoPaths.Kind.CARRIAGE_GROUP;
            case BuilderRelayKinds.CONTENTS -> BuilderPhotoPaths.Kind.CONTENTS;
            case BuilderRelayKinds.PART -> BuilderPhotoPaths.Kind.PART;
            case BuilderRelayKinds.TRACK -> BuilderPhotoPaths.Kind.TRACK;
            case BuilderRelayKinds.PORTAL_ROOM -> BuilderPhotoPaths.Kind.PORTAL_ROOM;
            default -> BuilderPhotoPaths.Kind.CARRIAGE;
        };
    }

    /** A part's kind, which its id is only unique within; null for every other kind. */
    private static CarriagePartKind partKindOf(BuilderProfilePacket.Entry entry) {
        return BuilderRelayKinds.PART.equals(entry.kind())
                ? CarriagePartKind.fromId(entry.subKind())
                : null;
    }

    /** As above for a track template. A portal room is stored under its own fixed kind. */
    private static TrackKind trackKindOf(BuilderProfilePacket.Entry entry) {
        if (BuilderRelayKinds.PORTAL_ROOM.equals(entry.kind())) return TrackKind.PORTAL_ROOM;
        return BuilderRelayKinds.TRACK.equals(entry.kind()) ? TrackKind.fromId(entry.subKind()) : null;
    }

    /**
     * The line under the grid. Four different empties, and they mean different things: waiting on the
     * relay, a relay that never answered, a feature that is off, and a player who simply hasn't
     * uploaded anything yet.
     */
    private Component statusNote() {
        if (status == null) return Component.translatable("gui.dungeontrain.builder.profile.loading");
        if (status == BuilderProfilePacket.Status.DISABLED) {
            return Component.translatable("gui.dungeontrain.builder.profile.disabled");
        }
        if (status == BuilderProfilePacket.Status.UNAVAILABLE) {
            return Component.translatable("gui.dungeontrain.builder.profile.unavailable");
        }
        if (builds.isEmpty()) return Component.translatable("gui.dungeontrain.builder.profile.empty");
        BuilderProfilePacket.Entry entry = selectedBuild();
        if (entry == null) return Component.translatable("gui.dungeontrain.builder.profile.pick");
        // A flagged build is withheld from the pool however published it is, and this is the only place
        // its author could ever be told why theirs isn't turning up in anyone's train.
        if ("flagged".equals(entry.flag()) || "rejected".equals(entry.flag())) {
            return Component.translatable("gui.dungeontrain.builder.profile.withheld");
        }
        // A carriage is only placed into a stage it belongs to, and a build authored without one
        // belongs to none — so it can be submitted and still never appear anywhere. Said here because
        // "on the train and never seen" is indistinguishable from a broken feature otherwise.
        if (BuilderRelayKinds.canJoinTheTrain(entry.kind()) && entry.stage().isEmpty()) {
            return Component.translatable("gui.dungeontrain.builder.profile.no_stage");
        }
        return null;
    }

    private float frameSeconds() {
        long now = System.nanoTime();
        long previous = lastFrameNanos;
        lastFrameNanos = now;
        return previous == 0L ? 0.0F : Math.min((now - previous) / 1.0E9F, MAX_FRAME_SECONDS);
    }

    @Override
    public void removed() {
        super.removed();
        BuilderProfileState.listen(null);
        BuilderTileMeshCache.clear();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(lastScreen);
    }
}
