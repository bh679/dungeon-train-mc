package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderLabels;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.relay.BuilderRelayDownload;
import games.brennan.dungeontrain.builder.relay.BuilderRelayInstall;
import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.client.menu.CommandRunner;
import games.brennan.dungeontrain.client.menu.EditorTemplateJump;
import games.brennan.dungeontrain.client.menu.UnsavedCheckScreen;
import games.brennan.dungeontrain.net.BuilderOpenPacket;
import games.brennan.dungeontrain.net.BuilderProfileActionPacket;
import games.brennan.dungeontrain.net.BuilderProfileDownloadPacket;
import games.brennan.dungeontrain.net.BuilderProfileDownloadResultPacket;
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
 * <b>Load into editor</b> is what fetches one back, writing it into this install's library so it can
 * be opened like anything else. In a builder world it is opened straight away, through the ordinary
 * Open path.</p>
 *
 * <p>Only a whole carriage can be submitted ({@link BuilderRelayKinds#canJoinTheTrain}); every other
 * kind the builder authors is a piece of something rather than a thing a train slot can hold, so its
 * tile says where it lives and offers nothing to press.</p>
 *
 * <p>Submitting is a request, not an outcome. A submitted build waits for a person to look at it
 * ({@link BuilderReviewState}), and until they do it is neither in the profile nor on the train — so
 * a submittable carriage's tile reads from its review state rather than from the published flag,
 * which only says what its author asked for. This screen is the one place either half of that can be
 * told to the person who made it.</p>
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
    private static final int ACTION_GAP = 4;

    /** Longest timestep the tile spin will accept, so a stalled frame doesn't fling it round. */
    private static final float MAX_FRAME_SECONDS = 0.1F;

    private final Screen lastScreen;

    private List<BuilderProfilePacket.Entry> builds = List.of();
    private BuilderProfilePacket.Status status = null;
    private BuilderTemplateGridLayout grid;
    private int scrollY;
    private int selected = -1;
    private Button actionButton;
    private Button downloadButton;

    /**
     * What the last download did, shown under the grid until the selection changes.
     *
     * <p>Not folded into {@link #statusNote}'s reasoning: that method answers "why is this list the
     * way it is", which is about the relay and the player's settings. This answers "what happened
     * when I pressed the button", which is about one build and one press, and the two would give
     * different answers to the same pixel.</p>
     */
    private Component downloadNote;

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
        BuilderProfileState.listenForDownloads(this::onDownload);
        DungeonTrainNet.sendToServer(new BuilderProfileRequestPacket());

        this.spin.clear();
        this.lastFrameNanos = 0L;
        rebuild();
    }

    /** A profile arrived. Keep the selection on the same build where it survives the refresh. */
    private void onProfile(BuilderProfilePacket packet) {
        int selectedId = selectedBuild() == null ? -1 : selectedBuild().relayId();
        this.downloadNote = null;
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

        // Two actions, side by side across the same width the Back button occupies: what the build
        // does on the relay, and what it does on this machine.
        int half = (BACK_BUTTON_WIDTH - ACTION_GAP) / 2;
        int left = this.width / 2 - BACK_BUTTON_WIDTH / 2;
        this.actionButton = Button.builder(actionLabel(), b -> submitSelected())
                .bounds(left, gridBottom + 4, half, 20)
                .build();
        this.actionButton.active = canActOnSelection();
        addRenderableWidget(this.actionButton);

        this.downloadButton = Button.builder(
                        Component.translatable("gui.dungeontrain.builder.profile.load_into_editor"),
                        b -> downloadSelected())
                .bounds(left + half + ACTION_GAP, gridBottom + 4, half, 20)
                .build();
        this.downloadButton.active = selectedBuild() != null;
        addRenderableWidget(this.downloadButton);

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
     * train can hold. Withdrawing is always allowed on a submitted build — the relay is what refuses,
     * when somebody else is out riding it.
     */
    private boolean canActOnSelection() {
        BuilderProfilePacket.Entry entry = selectedBuild();
        return entry != null && BuilderRelayKinds.canJoinTheTrain(entry.kind());
    }

    private Component actionLabel() {
        BuilderProfilePacket.Entry entry = selectedBuild();
        if (entry == null) return Component.translatable("gui.dungeontrain.builder.profile.submit_for_review");
        if (!BuilderRelayKinds.canJoinTheTrain(entry.kind())) {
            return Component.translatable("gui.dungeontrain.builder.profile.not_a_carriage");
        }
        return Component.translatable(entry.published()
                ? "gui.dungeontrain.builder.profile.withdraw_submission"
                : "gui.dungeontrain.builder.profile.submit_for_review");
    }

    private void submitSelected() {
        BuilderProfilePacket.Entry entry = selectedBuild();
        if (entry == null || !BuilderRelayKinds.canJoinTheTrain(entry.kind())) return;
        DungeonTrainNet.sendToServer(new BuilderProfileActionPacket(entry.relayId(), !entry.published()));
        // The server re-reads the profile once the relay answers, which lands back on this screen
        // through onProfile — so nothing is assumed to have worked here.
        this.actionButton.active = false;
    }

    /**
     * Ask the server for this build's blocks and write them into the local library.
     *
     * <p>Offered for every kind, unlike Submit to the train: a room, a part and a length of track are
     * all things their author may want back on a new machine, and only the train has an opinion about
     * which of them is a carriage.</p>
     */
    private void downloadSelected() {
        BuilderProfilePacket.Entry entry = selectedBuild();
        if (entry == null) return;
        DungeonTrainNet.sendToServer(new BuilderProfileDownloadPacket(entry.relayId()));
        this.downloadButton.active = false;
        this.downloadNote = Component.translatable("gui.dungeontrain.builder.profile.downloading");
    }

    /**
     * A download finished.
     *
     * <p>On success in a builder world the build is opened immediately, through the ordinary
     * {@link BuilderOpenPacket} — which is what makes this a load rather than a file copy, and means
     * the unsaved-work prompt, the spawn standoff and the photo backfill all behave exactly as they
     * do for any other Open. Outside a builder world (the in-world Train Editor's menu) there is no
     * plot to stamp it on, so the build simply joins that editor's lists.</p>
     */
    private void onDownload(BuilderProfileDownloadResultPacket packet) {
        this.downloadNote = Component.translatable(noteKeyFor(packet.outcome()));
        if (this.downloadButton != null) this.downloadButton.active = selectedBuild() != null;

        // A name already in use is a question, not a refusal: the player chooses which copy keeps it.
        if (packet.outcome() == BuilderRelayDownload.Outcome.ALREADY_HERE) {
            BuilderProfilePacket.Entry entry = selectedBuild();
            if (entry != null) {
                this.minecraft.setScreen(new BuilderProfileCollisionScreen(this, entry.buildName(),
                        (resolution, name) -> resolveDownload(entry.relayId(), resolution, name)));
            }
            return;
        }
        if (packet.outcome() != BuilderRelayDownload.Outcome.INSTALLED) return;

        BuilderPhotoPaths.Kind kind = BuilderPhotoPaths.Kind.fromId(packet.kindId()).orElse(null);
        if (kind == null) return;
        if (BuilderWorldCheck.isBuilderWorld()) {
            openInBuilder(kind, packet);
            return;
        }
        openInEditor(kind, packet);
    }

    /**
     * Open what was just installed on a builder plot, switching the builder into the mode that
     * authors it.
     *
     * <p>The ordinary {@link BuilderOpenPacket}, so this is the same open the Open grid performs —
     * mode switch, unsaved-work prompt, spawn standoff and photo backfill all included. Unforced: if
     * there is unsaved work the open path puts its own Save / Discard prompt up, and the downloaded
     * build is on disk either way, so nothing is lost by the refusal.</p>
     */
    private void openInBuilder(BuilderPhotoPaths.Kind kind, BuilderProfileDownloadResultPacket packet) {
        BuilderMode mode = BuilderRelayKinds.modeFor(kind);
        if (mode == null) return;
        DungeonTrainNet.sendToServer(kind == BuilderPhotoPaths.Kind.TRACK
                ? BuilderOpenPacket.forTrack(mode.id(), TrackKind.fromId(packet.subKind()),
                        packet.id(), false)
                : new BuilderOpenPacket(mode.id(), kind.id(), packet.id(),
                        kind == BuilderPhotoPaths.Kind.PART ? packet.subKind() : "", false));
        closeToGame();
    }

    /**
     * Take the player to what was just installed in the in-world Train Editor.
     *
     * <p>Two cases, and the difference is whether the editor has to be moved between categories.
     * Inside the right one already, the per-template enter command teleports and nothing is
     * disturbed. From a different category the switch has to happen first — and that switch clears
     * and restamps every plot, which silently destroys unsaved edits, so it goes through the same
     * {@link UnsavedCheckScreen} the Enter menu uses rather than around it. A clean editor never
     * sees that screen: it dispatches and closes on its own.</p>
     *
     * <p>Does nothing when no editor session is running — the player downloaded a build from the
     * pause menu of an ordinary world, where there is no plot to stand them on. The build is
     * installed and the screen has already said so.</p>
     */
    private void openInEditor(BuilderPhotoPaths.Kind kind, BuilderProfileDownloadResultPacket packet) {
        if (!EditorStatusHudOverlay.isActive()) return;
        String target = EditorTemplateJump.categoryIdFor(kind, packet.subKind());
        if (target == null) return;   // nothing in the editor holds this kind — a carriage group
        String enter = EditorTemplateJump.enterCommandFor(kind, packet.id(), packet.subKind());

        String current = EditorStatusHudOverlay.category().toLowerCase(java.util.Locale.ROOT);
        if (target.equals(current)) {
            if (enter != null) CommandRunner.run(enter);
            closeToGame();
            return;
        }
        closeToGame();
        CommandMenuState.openAt(new UnsavedCheckScreen(target, enter == null ? "" : enter));
    }

    /**
     * Send the second press: the same download, with the player's answer to the name collision.
     *
     * <p>Re-selects nothing and assumes nothing — the result comes back through {@link #onDownload}
     * like the first one did, so a resolution that also fails (the new name taken too) puts the
     * question up again rather than silently doing nothing.</p>
     */
    private void resolveDownload(int relayId, BuilderRelayInstall.Resolution resolution, String name) {
        DungeonTrainNet.sendToServer(new BuilderProfileDownloadPacket(relayId, resolution, name));
        this.downloadNote = Component.translatable("gui.dungeontrain.builder.profile.downloading");
        if (this.downloadButton != null) this.downloadButton.active = false;
    }

    /**
     * Close all the way to the game, not back to whatever opened this screen.
     *
     * <p>{@link #onClose} returns to {@code lastScreen}, which is the pause menu when My Builds was
     * opened from it — so the player would be teleported to their build and left looking at the Esc
     * menu. A load ends with the build in front of you or it hasn't finished.</p>
     */
    private void closeToGame() {
        this.minecraft.setScreen(null);
    }

    /** The line to show for an outcome — each sends the player somewhere different. */
    private static String noteKeyFor(BuilderRelayDownload.Outcome outcome) {
        return switch (outcome) {
            case INSTALLED -> "gui.dungeontrain.builder.profile.downloaded";
            case ALREADY_HERE -> "gui.dungeontrain.builder.profile.download_already_here";
            case NAME_TAKEN -> "gui.dungeontrain.builder.profile.download_name_taken";
            case NOT_YOURS -> "gui.dungeontrain.builder.profile.download_not_yours";
            case GONE -> "gui.dungeontrain.builder.profile.gone_short";
            case UNAVAILABLE -> "gui.dungeontrain.builder.profile.unavailable";
            case UNSUPPORTED -> "gui.dungeontrain.builder.profile.download_unsupported";
            case FAILED -> "gui.dungeontrain.builder.profile.download_failed";
        };
    }

    // ---- input ----

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !builds.isEmpty()) {
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
                        photoKindOf(entry), entry.buildName(), partKindOf(entry), trackKindOf(entry),
                        labelFor(entry),
                        x, y, grid.cellWidth(), grid.cellHeight(), hovered || i == selected, true,
                        spin.advance(String.valueOf(entry.relayId()), hovered, seconds),
                        borderColourOf(entry));
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
        // A submittable carriage says where it stands with the reviewer; everything else — and a
        // carriage nobody has submitted — falls back to where it lives, which is all there is to say.
        String key = BuilderRelayKinds.canJoinTheTrain(entry.kind())
                ? BuilderReviewState.labelKeyFor(entry.review())
                : null;
        if (key == null) {
            key = entry.published()
                    ? "gui.dungeontrain.builder.profile.on_train"
                    : "gui.dungeontrain.builder.profile.in_profile";
        }
        return Component.literal(name + " · ").append(Component.translatable(key));
    }

    /**
     * The colour to ring this tile with: where the build stands with the reviewer, said in a way that
     * survives not reading the caption.
     *
     * <p>Only a submittable kind gets one. A portal room or a shell part has no submission state to be
     * in — colouring it would invent a status for something that was never asked about.</p>
     */
    private static int borderColourOf(BuilderProfilePacket.Entry entry) {
        return BuilderRelayKinds.canJoinTheTrain(entry.kind())
                ? BuilderReviewState.borderColourFor(entry.review())
                : BuilderReviewState.BORDER_NONE;
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
     * The line under the grid. Six different empties, and they mean different things: waiting on the
     * relay, a relay that never answered, a feature the server has off, a player who hasn't granted
     * network consent, a consent answer still in flight, and a player who simply hasn't uploaded
     * anything yet. Only two of those are anything the player can act on, and telling them apart is
     * the whole point — a blanket "it's off" sends someone to a setting that isn't the problem.
     */
    private Component statusNote() {
        if (downloadNote != null) return downloadNote;
        if (status == null) return Component.translatable("gui.dungeontrain.builder.profile.loading");
        if (status == BuilderProfilePacket.Status.DISABLED) {
            return Component.translatable("gui.dungeontrain.builder.profile.disabled");
        }
        if (status == BuilderProfilePacket.Status.NO_CONSENT) {
            return Component.translatable("gui.dungeontrain.builder.profile.no_consent");
        }
        if (status == BuilderProfilePacket.Status.CONSENT_PENDING) {
            return Component.translatable("gui.dungeontrain.builder.profile.consent_pending");
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
        // A declined build is a decision about this build and outranks everything below: fixing its
        // stage would not put it on the train, and saying "waiting" of it would be untrue.
        if (BuilderReviewState.DECLINED.equals(BuilderReviewState.of(entry.review()))) {
            return Component.translatable("gui.dungeontrain.builder.profile.review.declined_note");
        }
        // A carriage is only placed into a stage it belongs to, and a build authored without one
        // belongs to none — so it can be submitted and still never appear anywhere. Said here because
        // "on the train and never seen" is indistinguishable from a broken feature otherwise.
        if (BuilderRelayKinds.canJoinTheTrain(entry.kind()) && entry.stage().isEmpty()) {
            return Component.translatable("gui.dungeontrain.builder.profile.no_stage");
        }
        // Last, and deliberately below the stage line: waiting is the ordinary, healthy state, while a
        // build with no stage has something its author can still fix while it waits.
        String reviewNote = BuilderReviewState.noteKeyFor(entry.review());
        if (reviewNote != null) return Component.translatable(reviewNote);
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
        BuilderProfileState.listenForDownloads(null);
        BuilderTileMeshCache.clear();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(lastScreen);
    }
}
