package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderLabels;
import games.brennan.dungeontrain.builder.BuilderNewOptions;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.relay.BuilderRelayDownload;
import games.brennan.dungeontrain.builder.relay.BuilderRelayInstall;
import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.client.menu.CommandRunner;
import games.brennan.dungeontrain.client.menu.EditorTemplateJump;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.BuilderCreatorResultsPacket;
import games.brennan.dungeontrain.net.BuilderOpenPacket;
import games.brennan.dungeontrain.net.BuilderFavouritePacket;
import games.brennan.dungeontrain.net.BuilderProfileActionPacket;
import games.brennan.dungeontrain.net.BuilderProfileDownloadPacket;
import games.brennan.dungeontrain.net.BuilderProfileDownloadResultPacket;
import games.brennan.dungeontrain.net.BuilderProfilePacket;
import games.brennan.dungeontrain.net.BuilderProfileRequestPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Set;

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
 *
 * <p>The header names whose builds these are. On a DEV BUILD that name is a button: it opens
 * {@link BuilderCreatorSearchScreen}, and picking a builder relists this screen against their
 * profile — how a developer looks at a player's work to reproduce a problem. A foreign profile is
 * read-only apart from <b>Load into editor</b>: submitting or withdrawing somebody else's build is
 * not something this screen will do, and a build loaded from one arrives as a local copy with no
 * link back to their relay row.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderProfileScreen extends Screen {

    private static final int TITLE_TOP = 14;
    /** The owner row sits between the title and the filter chips, and is present on every build. */
    private static final int OWNER_TOP = 26;
    private static final int OWNER_BUTTON_H = 16;
    private static final int TARGET_BUTTON_W = 56;
    /** Green while the screen is reading PRODUCTION; a dim grey on this build's own relay. */
    private static final int LIVE_DOT = 0x55FF55;
    private static final int DEV_DOT = 0x808080;
    private static final int BACK_BUTTON_WIDTH = 200;
    private static final int BACK_BUTTON_BOTTOM_MARGIN = 28;
    private static final int STATUS_GAP = 14;
    private static final int SCROLL_STEP = 24;
    private static final int NOTE_COLOUR = 0xA0A0A0;
    private static final int ACTION_GAP = 4;

    /** The one row of controls above the grid: two filter chips and the tiles-per-row chip. */
    private static final int CONTROL_ROW_H = 20;
    private static final int CONTROL_GAP = 4;
    private static final int FILTER_WIDTH = 96;
    /** The favourite chip: two short options, so it does not need the others' width. */
    private static final int FAVOURITE_WIDTH = 68;

    /**
     * The kinds a profile can hold, in the order the chip offers them — {@link BuilderProfileFilters#ALL}
     * first, then a carriage, which is the only kind a train can hold and so the one a builder is
     * usually looking for.
     */
    private static final List<BuilderProfileFilterButton.Option> TYPE_OPTIONS = List.of(
            new BuilderProfileFilterButton.Option(BuilderProfileFilters.ALL,
                    "gui.dungeontrain.builder.profile.type.all"),
            new BuilderProfileFilterButton.Option(BuilderRelayKinds.CARRIAGE,
                    "gui.dungeontrain.builder.profile.type.carriage"),
            new BuilderProfileFilterButton.Option(BuilderRelayKinds.CARRIAGE_GROUP,
                    "gui.dungeontrain.builder.profile.type.carriage_group"),
            new BuilderProfileFilterButton.Option(BuilderRelayKinds.CONTENTS,
                    "gui.dungeontrain.builder.profile.type.contents"),
            new BuilderProfileFilterButton.Option(BuilderRelayKinds.PART,
                    "gui.dungeontrain.builder.profile.type.part"),
            new BuilderProfileFilterButton.Option(BuilderRelayKinds.TRACK,
                    "gui.dungeontrain.builder.profile.type.track"),
            new BuilderProfileFilterButton.Option(BuilderRelayKinds.PORTAL_ROOM,
                    "gui.dungeontrain.builder.profile.type.portal_room"));

    /** The review states, in funnel order: never asked → waiting → decided. */
    private static final List<BuilderProfileFilterButton.Option> STATUS_OPTIONS = List.of(
            new BuilderProfileFilterButton.Option(BuilderProfileFilters.ALL,
                    "gui.dungeontrain.builder.profile.status.all"),
            new BuilderProfileFilterButton.Option(BuilderReviewState.NONE,
                    "gui.dungeontrain.builder.profile.status.none"),
            // The three verdicts carry their tile colour onto the chip, so the chip answers in the
            // same language the grid does. "All" and "not submitted" have no colour on a tile either.
            new BuilderProfileFilterButton.Option(BuilderReviewState.SUBMITTED,
                    "gui.dungeontrain.builder.profile.status.submitted",
                    BuilderReviewState.BORDER_SUBMITTED),
            new BuilderProfileFilterButton.Option(BuilderReviewState.ACCEPTED,
                    "gui.dungeontrain.builder.profile.status.accepted",
                    BuilderReviewState.BORDER_ACCEPTED),
            new BuilderProfileFilterButton.Option(BuilderReviewState.DECLINED,
                    "gui.dungeontrain.builder.profile.status.declined",
                    BuilderReviewState.BORDER_DECLINED));

    /**
     * The favourite axis, in the order the chip offers it — everything first, then the narrowing.
     *
     * <p>Two options rather than three: there is no "only the ones I haven't starred", because a star
     * marks the few worth returning to out of many and narrowing away from them is asking for the pile
     * already on screen.</p>
     */
    private static final List<BuilderProfileFilterButton.Option> FAVOURITE_OPTIONS = List.of(
            new BuilderProfileFilterButton.Option(BuilderProfileFilters.ALL,
                    "gui.dungeontrain.builder.profile.favourite.all"),
            new BuilderProfileFilterButton.Option(BuilderProfileFilters.STARRED,
                    "gui.dungeontrain.builder.profile.favourite.starred"));

    /**
     * Where the player left the two chips.
     *
     * <p>Static, so closing the screen and reopening it — which is what pressing Submit and coming
     * back amounts to — does not throw away the narrowing they set up. Not persisted to disk, unlike
     * the tiles-per-row count: that is a preference about how you like to look at things, while a
     * filter is where you are in a job, and a filter still applied a week later would read as a
     * profile that had lost most of its builds.</p>
     */
    private static String typeFilter = BuilderProfileFilters.ALL;
    private static String statusFilter = BuilderProfileFilters.ALL;
    private static String favouriteFilter = BuilderProfileFilters.ALL;

    /** Longest timestep the tile spin will accept, so a stalled frame doesn't fling it round. */
    private static final float MAX_FRAME_SECONDS = 0.1F;

    private final Screen lastScreen;

    /** Whose profile to show: empty for this player's own, otherwise a builder picked from the search. */
    private final String viewedUuid;
    /** What to call them. Blank until the player's own name is known or a creator has been picked. */
    private String viewedName;

    private List<BuilderProfilePacket.Entry> builds = List.of();

    /**
     * {@link #builds} narrowed by the two chips — what the grid lays out, what a click indexes into,
     * and what {@link #selected} is an index of. Everything downstream of the filter reads this, so
     * there is no path on which a hidden build can be selected or acted on.
     */
    private List<BuilderProfilePacket.Entry> shown = List.of();

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

    /**
     * The answers the last download press carried, so a question raised by that press can be
     * answered without losing them. The unsaved-edits prompt can arrive on the second press of a
     * collision flow — the player has already said "load it as bb_2" — and replaying the download
     * has to carry that choice, not start over from a first press.
     */
    private BuilderRelayInstall.Resolution lastResolution = BuilderRelayInstall.Resolution.AS_IS;
    private String lastChosenName = "";

    private final BuilderTileSpin spin = new BuilderTileSpin();
    private long lastFrameNanos;

    /**
     * My Builds as the menus open it — showing whoever was last looked at, which is the player
     * themselves until a builder has been picked this session.
     */
    public BuilderProfileScreen(Screen lastScreen) {
        this(lastScreen, BuilderProfileState.viewedUuid(), BuilderProfileState.viewedName());
    }

    /**
     * As above for one particular builder — the dev-build path, entered by picking a name in
     * {@link BuilderCreatorSearchScreen}. An empty {@code viewedUuid} is the player's own profile.
     */
    public BuilderProfileScreen(Screen lastScreen, String viewedUuid, String viewedName) {
        super(Component.translatable("gui.dungeontrain.builder.profile.title"));
        this.lastScreen = lastScreen;
        this.viewedUuid = viewedUuid == null ? "" : viewedUuid;
        this.viewedName = viewedName == null ? "" : viewedName;
    }

    /** Whether the profile on screen belongs to somebody other than the player. */
    private boolean viewingOther() {
        return !viewedUuid.isEmpty();
    }

    @Override
    protected void init() {
        // The cached list is what makes a reopened screen instant, but it is a cache of ONE profile —
        // showing it under another builder's name would be worse than showing nothing.
        BuilderProfilePacket latest = BuilderProfileState.latest();
        if (latest != null && isForViewed(latest)) {
            this.builds = latest.builds();
            this.status = latest.status();
            if (latest.mine() && !latest.ownerName().isEmpty()) this.viewedName = latest.ownerName();
        } else {
            this.builds = List.of();
            this.status = null;
        }
        // Always re-ask on the way in. The cached list is what makes a reopened screen instant, but it
        // may predate a save, a publish, or a build somebody else's world just returned.
        BuilderProfileState.listen(this::onProfile);
        BuilderProfileState.listenForDownloads(this::onDownload);
        DungeonTrainNet.sendToServer(new BuilderProfileRequestPacket(viewedUuid, BuilderProfileState.live()));

        this.spin.clear();
        this.lastFrameNanos = 0L;
        rebuild();
    }

    /**
     * A profile arrived. Keep the selection on the same build where it survives the refresh.
     *
     * <p>An answer about a profile this screen is no longer showing is dropped: on a dev build two
     * asks can be in flight (the player switched builders while the first was out), and the network
     * does not promise to answer them in the order they were sent.</p>
     */
    private void onProfile(BuilderProfilePacket packet) {
        if (!isForViewed(packet)) return;
        if (packet.mine() && !packet.ownerName().isEmpty()) this.viewedName = packet.ownerName();
        int selectedId = selectedBuild() == null ? -1 : selectedBuild().relayId();
        this.downloadNote = null;
        this.builds = packet.builds();
        this.status = packet.status();
        refilter(selectedId);
        rebuild();
    }

    /**
     * Re-apply the chips, keeping the selection on the same BUILD where the narrowing still shows it.
     *
     * <p>By relay id rather than by index, because the index means something different in every
     * filtered list — keeping the number would silently move the selection onto whichever build now
     * happens to sit there, and the next press would act on a build the player never chose.</p>
     */
    private void refilter(int keepRelayId) {
        this.shown = BuilderProfileFilters.apply(builds, typeFilter, statusFilter, favouriteFilter);
        this.selected = -1;
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).relayId() == keepRelayId) {
                this.selected = i;
                break;
            }
        }
    }

    /** A chip moved: same profile, different slice of it. */
    private void onFilterChanged() {
        int selectedId = selectedBuild() == null ? -1 : selectedBuild().relayId();
        this.downloadNote = null;
        refilter(selectedId);
        this.scrollY = 0;   // a different list — an inherited offset would land mid-nowhere
        rebuild();
    }

    /** Whether this reply is about the profile on screen — see {@link #onProfile}. */
    private boolean isForViewed(BuilderProfilePacket packet) {
        return viewingOther() ? viewedUuid.equals(packet.ownerUuid()) : packet.mine();
    }

    /**
     * Show a different builder's profile, or the player's own when {@code creator} is null.
     *
     * <p>A whole new screen rather than a relist: everything on this one — the selection, the note
     * about a download, the cached tiles, the profile the state holder is caching — belongs to the
     * builds that were listed, and a fresh screen asks for the right profile in its own
     * {@link #init} instead of the two of them racing.</p>
     */
    private void viewProfile(BuilderCreatorResultsPacket.Creator creator) {
        // Recorded before the screen swaps, so the menus reopen on this builder too — not just the
        // screen being replaced here.
        BuilderProfileState.setViewed(creator == null ? "" : creator.uuid(),
                creator == null ? "" : creator.name());
        this.minecraft.setScreen(creator == null
                ? new BuilderProfileScreen(lastScreen)
                : new BuilderProfileScreen(lastScreen, creator.uuid(), creator.name()));
    }

    /** This player's own name, for the header before any profile has come back to confirm it. */
    private static String ownName() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getUser() == null ? "" : mc.getUser().getName();
    }

    private void rebuild() {
        clearWidgets();

        // Whose builds these are, above the chips. A release build has no other profile to reach, so
        // the name is drawn as text in render(); a dev build makes it the way in to somebody else's.
        if (DungeonTrain.isDevBuild()) {
            Component name = ownerLine();
            int nameWidth = Math.max(this.font.width(name) + 16, 80);
            addRenderableWidget(Button.builder(name,
                            b -> this.minecraft.setScreen(new BuilderCreatorSearchScreen(this, this::viewProfile)))
                    .bounds(this.width / 2 - nameWidth / 2, OWNER_TOP, nameWidth, OWNER_BUTTON_H)
                    .build());
            if (viewingOther()) {
                addRenderableWidget(Button.builder(
                                Component.translatable("gui.dungeontrain.builder.profile.back_to_mine"),
                                b -> viewProfile(null))
                        .bounds(this.width / 2 + nameWidth / 2 + ACTION_GAP, OWNER_TOP, 100, OWNER_BUTTON_H)
                        .build());
            }
            // Which relay these builds come from. A dot rather than a sentence, because it is read at a
            // glance and its only job is to stop a developer mistaking production for their own pool.
            addRenderableWidget(Button.builder(targetLabel(), b -> toggleTarget())
                    .bounds(this.width / 2 - nameWidth / 2 - TARGET_BUTTON_W - ACTION_GAP, OWNER_TOP,
                            TARGET_BUTTON_W, OWNER_BUTTON_H)
                    .build());
        }

        // One row of controls, all three chips on it: the grid is the tightest thing on this screen
        // for vertical space, and two rows of narrowing above a wall of pictures would be furniture
        // competing with the thing it is meant to help you look at. Below the owner row, which is
        // there on every build — the two would otherwise land on the same pixels.
        int controlY = OWNER_TOP + OWNER_BUTTON_H + CONTROL_GAP;
        int rowWidth = FILTER_WIDTH * 2 + FAVOURITE_WIDTH + BuilderTilesPerRowButton.WIDTH
                + CONTROL_GAP * 3;
        int controlX = this.width / 2 - rowWidth / 2;
        addRenderableWidget(new BuilderProfileFilterButton(controlX, controlY, FILTER_WIDTH,
                CONTROL_ROW_H, TYPE_OPTIONS, () -> typeFilter,
                v -> { typeFilter = v; onFilterChanged(); },
                "gui.dungeontrain.builder.profile.type.tooltip"));
        addRenderableWidget(new BuilderProfileFilterButton(controlX + FILTER_WIDTH + CONTROL_GAP,
                controlY, FILTER_WIDTH, CONTROL_ROW_H, STATUS_OPTIONS, () -> statusFilter,
                v -> { statusFilter = v; onFilterChanged(); },
                "gui.dungeontrain.builder.profile.status.tooltip"));
        // Narrower than the other two: it has two short options where they have five and seven, and
        // giving it their width would be reserving space for text that is never going to be there.
        addRenderableWidget(new BuilderProfileFilterButton(
                controlX + (FILTER_WIDTH + CONTROL_GAP) * 2, controlY, FAVOURITE_WIDTH,
                CONTROL_ROW_H, FAVOURITE_OPTIONS, () -> favouriteFilter,
                v -> { favouriteFilter = v; onFilterChanged(); },
                "gui.dungeontrain.builder.profile.favourite.tooltip"));
        // The same chip the Open screen has, reading and writing the same stored count: it is one
        // answer to "how big do I like builder tiles", and two screens disagreeing about it would be
        // a bug rather than a choice.
        addRenderableWidget(new BuilderTilesPerRowButton(
                controlX + (FILTER_WIDTH + CONTROL_GAP) * 2 + FAVOURITE_WIDTH + CONTROL_GAP,
                controlY, CONTROL_ROW_H, this.width, this::rebuild));

        int gridTop = controlY + CONTROL_ROW_H + CONTROL_GAP;
        int gridBottom = this.height - BACK_BUTTON_BOTTOM_MARGIN - STATUS_GAP - 24;
        this.grid = BuilderTemplateGridLayout.of(this.width, gridTop, gridBottom, shown.size(),
                BuilderTilesPerRowButton.effectiveColumns(this.width));
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

        // The bottom row is split the same way the action row above it is, rather than Back taking the
        // whole width: Favourites is a place to go rather than something to do to the selected build,
        // so it belongs down here with Back and not up there with Submit.
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(left, this.height - BACK_BUTTON_BOTTOM_MARGIN, half, 20)
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.dungeontrain.builder.favourites.open"),
                        b -> this.minecraft.setScreen(new BuilderFavouritesScreen(lastScreen)))
                .bounds(left + half + ACTION_GAP, this.height - BACK_BUTTON_BOTTOM_MARGIN, half, 20)
                .build());
    }

    private BuilderProfilePacket.Entry selectedBuild() {
        return selected >= 0 && selected < shown.size() ? shown.get(selected) : null;
    }

    /**
     * Whether the button can do anything: a build has to be selected, and it has to be the kind a
     * train can hold. Withdrawing is always allowed on a submitted build — the relay is what refuses,
     * when somebody else is out riding it.
     */
    private boolean canActOnSelection() {
        BuilderProfilePacket.Entry entry = selectedBuild();
        // Never on somebody else's profile: putting their build on the train, or pulling it off, is
        // their decision to make and the relay would refuse it anyway (the action is authed by the
        // owner secret, which this world does not hold).
        return entry != null && !viewingOther() && BuilderRelayKinds.canJoinTheTrain(entry.kind());
    }

    private Component actionLabel() {
        BuilderProfilePacket.Entry entry = selectedBuild();
        if (viewingOther()) return Component.translatable("gui.dungeontrain.builder.profile.not_yours_short");
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
        if (entry == null || viewingOther() || !BuilderRelayKinds.canJoinTheTrain(entry.kind())) return;
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
        this.lastResolution = BuilderRelayInstall.Resolution.AS_IS;
        this.lastChosenName = "";
        DungeonTrainNet.sendToServer(new BuilderProfileDownloadPacket(entry.relayId(), viewedUuid, BuilderProfileState.live()));
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
                BuilderProfileCollisionScreen question = new BuilderProfileCollisionScreen(this,
                        entry.buildName(),
                        (resolution, name) -> resolveDownload(entry.relayId(), resolution, name));
                // What the server says is already in use, so the name box can open on a free name
                // and refuse a used one where the player is standing rather than a round trip later.
                question.setTakenNames(Set.copyOf(packet.takenNames()));
                this.minecraft.setScreen(question);
            }
            return;
        }

        // The name they chose was gone after all — a race, or a name only the server can see is
        // taken. Back to the same box, with the warning showing and the fresher list, rather than
        // out to the list with the answer thrown away.
        if (packet.outcome() == BuilderRelayDownload.Outcome.NAME_TAKEN) {
            BuilderProfilePacket.Entry entry = selectedBuild();
            if (entry != null && lastResolution != BuilderRelayInstall.Resolution.AS_IS) {
                Set<String> taken = Set.copyOf(packet.takenNames());
                BuilderRelayInstall.Resolution resolution = lastResolution;
                this.minecraft.setScreen(new BuilderProfileNameScreen(this, this,
                        Component.translatable(promptKeyFor(resolution),
                                Component.literal(BuilderLabels.pretty(entry.buildName()))),
                        BuilderNewOptions.firstFreeName(entry.buildName(), taken), taken, true,
                        chosen -> resolveDownload(entry.relayId(), resolution, chosen)));
            }
            return;
        }
        // Unsaved edits on the template this build would land on are the same shape of question, and
        // asked before anything is written: answering it replays the download with the overwrite
        // confirmed, cancelling leaves the file and the plot untouched.
        if (packet.outcome() == BuilderRelayDownload.Outcome.UNSAVED_EDITS) {
            BuilderProfilePacket.Entry entry = selectedBuild();
            if (entry != null) {
                // packet.id() is the name it would LAND on, which for a load-as-new is the name the
                // player typed rather than the build's own — that is the plot with the edits in it.
                this.minecraft.setScreen(new BuilderProfileUnsavedScreen(this, packet.id(),
                        lastResolution, lastChosenName,
                        (resolution, name) -> resolveDownload(entry.relayId(), resolution, name, true)));
            }
            return;
        }
        if (packet.outcome() != BuilderRelayDownload.Outcome.INSTALLED) return;

        // An install is the end of this menu either way: the build is on disk, and leaving the
        // player looking at the list they loaded it from is a step they would only have to undo.
        // Closed first so the open path's own screens — the unsaved-work prompt, the category
        // switch — land in front of the game rather than on top of a list nobody is reading.
        closeToGame();

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
    }

    /**
     * Take the player to what was just installed in the in-world Train Editor.
     *
     * <p>Two cases, and the difference is whether the editor has to be moved between categories.
     * Inside the right one already, the per-template enter command teleports and nothing is
     * disturbed. From anywhere else the switch runs first and the enter command follows it — the
     * order matters, because the switch restamps every plot and would wipe a teleport that ran
     * before it.</p>
     *
     * <p>No "save before switch?" list on the way through, unlike the Enter menu. That list names
     * every dirty plot in the session, which is the right question for a menu whose whole purpose is
     * the switch and the wrong one for a player who pressed Load on one build: the template this
     * download actually lands on is asked about server-side, before anything is written
     * ({@link BuilderRelayDownload.Outcome#UNSAVED_EDITS}), and that question is the one worth
     * stopping for.</p>
     *
     * <p>Standing in an editor plot is deliberately <em>not</em> required. A load ends with the
     * build in front of you, and the player who has just downloaded one has said plainly enough
     * where they want to be — so no session yet is simply the switch case, the same path the Enter
     * menu takes when it moves between categories. What is required is being allowed to run the
     * editor commands at all ({@link #canRunEditorCommands}), which is what keeps a plain survival
     * download from restamping the world around someone who only wanted the file.</p>
     */
    private void openInEditor(BuilderPhotoPaths.Kind kind, BuilderProfileDownloadResultPacket packet) {
        String target = EditorTemplateJump.categoryIdFor(kind, packet.subKind());
        // Nothing in the editor holds a carriage group, and a player without the editor's commands
        // can't be sent anywhere — either way the build is installed and the menu is done.
        if (target == null || !canRunEditorCommands()) return;
        String enter = EditorTemplateJump.enterCommandFor(kind, packet.id(), packet.subKind());

        String current = EditorStatusHudOverlay.category().toLowerCase(java.util.Locale.ROOT);
        if (target.equals(current)) {
            if (enter != null) CommandRunner.run(enter);
            return;
        }
        CommandRunner.run("dungeontrain editor " + target);
        if (enter != null) CommandRunner.run(enter);
    }

    /**
     * Whether this player can run the editor's commands, which is the client-side half of the
     * server's permission check — the op level the server synced at login.
     */
    private boolean canRunEditorCommands() {
        return this.minecraft != null && this.minecraft.player != null
                && this.minecraft.player.hasPermissions(2);
    }

    /**
     * Send the second press: the same download, with the player's answer to the name collision.
     *
     * <p>Re-selects nothing and assumes nothing — the result comes back through {@link #onDownload}
     * like the first one did, so a resolution that also fails (the new name taken too) puts the
     * question up again rather than silently doing nothing.</p>
     */
    private void resolveDownload(int relayId, BuilderRelayInstall.Resolution resolution, String name) {
        resolveDownload(relayId, resolution, name, false);
    }

    /** As above, with the player's answer to the unsaved-edits question carried alongside. */
    private void resolveDownload(int relayId, BuilderRelayInstall.Resolution resolution, String name,
                                 boolean overwriteUnsaved) {
        this.lastResolution = resolution;
        this.lastChosenName = name == null ? "" : name;
        DungeonTrainNet.sendToServer(new BuilderProfileDownloadPacket(relayId, resolution, name, viewedUuid,
                BuilderProfileState.live(), overwriteUnsaved));
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

    /**
     * Which name the prompt is asking for — the downloaded copy's, or the local build's on its way
     * out of the road. The same two keys {@code BuilderProfileChoiceScreen} uses, so a re-ask after a
     * refusal reads as the question the player was already answering.
     */
    private static String promptKeyFor(BuilderRelayInstall.Resolution resolution) {
        return resolution == BuilderRelayInstall.Resolution.RENAME_EXISTING
                ? "gui.dungeontrain.builder.profile.name.rename_existing"
                : "gui.dungeontrain.builder.profile.name.load_as_new";
    }

    /** The line to show for an outcome — each sends the player somewhere different. */
    static String noteKeyFor(BuilderRelayDownload.Outcome outcome) {
        return switch (outcome) {
            case INSTALLED -> "gui.dungeontrain.builder.profile.downloaded";
            case ALREADY_HERE -> "gui.dungeontrain.builder.profile.download_already_here";
            case NAME_TAKEN -> "gui.dungeontrain.builder.profile.download_name_taken";
            case UNSAVED_EDITS -> "gui.dungeontrain.builder.profile.download_unsaved";
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
        if (button == 0 && !shown.isEmpty()) {
            // The star is tested BEFORE the cell, because it sits inside one: the other order would
            // mean the cell swallowed the click and the star could never be pressed at all.
            for (int i = 0; i < shown.size(); i++) {
                if (grid.isVisible(i, scrollY) && grid.isOverStar(i, mouseX, mouseY, scrollY)) {
                    toggleFavourite(shown.get(i));
                    return true;
                }
            }
            int index = grid.indexAt(mouseX, mouseY, scrollY, shown.size());
            if (index >= 0) {
                this.selected = index;
                this.downloadNote = null;
                rebuild();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Star or un-star one build.
     *
     * <p>Optimistic: the tile flips now and the packet follows. A star that waited on a round trip
     * through the server to the relay before it filled in would read as a broken button, and the cost
     * of being wrong is small and self-correcting — the next listing carries the truth.</p>
     *
     * <p>Recorded on the cached profile as well as the visible list, so closing and reopening the
     * screen inside one session does not show the star snapping back to where it was.</p>
     */
    private void toggleFavourite(BuilderProfilePacket.Entry entry) {
        boolean next = !entry.favourite();
        DungeonTrainNet.sendToServer(
                BuilderFavouritePacket.forBuild(entry.relayId(), next, BuilderProfileState.live()));
        BuilderProfileState.noteFavourite(entry.relayId(), next);
        this.builds = BuilderProfileState.builds();
        int keep = selectedBuild() == null ? -1 : selectedBuild().relayId();
        refilter(keep);
        rebuild();
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
        if (!DungeonTrain.isDevBuild()) {
            // On a dev build the same line is a button, added in rebuild().
            g.drawCenteredString(this.font, ownerLine(), this.width / 2, OWNER_TOP + 4, NOTE_COLOUR);
        }

        if (!shown.isEmpty()) {
            float seconds = frameSeconds();
            BuilderTileMeshCache.beginFrame();
            g.enableScissor(0, grid.topY(), this.width, grid.bottomY());
            for (int i = 0; i < shown.size(); i++) {
                if (!grid.isVisible(i, scrollY)) continue;
                BuilderProfilePacket.Entry entry = shown.get(i);
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
                        badgeOf(entry), grid.badgeSize());
                BuilderTemplateTile.renderStar(g, grid.starX(i), grid.starY(i, scrollY),
                        grid.starSize(), entry.favourite(),
                        grid.isOverStar(i, mouseX, mouseY, scrollY));
            }
            g.disableScissor();
        }

        Component note = statusNote();
        if (note != null) {
            g.drawCenteredString(this.font, note, this.width / 2,
                    this.height - BACK_BUTTON_BOTTOM_MARGIN - STATUS_GAP + 2, NOTE_COLOUR);
        }
    }

    /** The relay-target button: a green dot on live, a dim one on this build's own relay. */
    private Component targetLabel() {
        boolean live = BuilderProfileState.live();
        // The dot carries the state and the word names it: colour alone would be a poor signal, and a
        // word alone is not readable at a glance from across the header.
        return Component.literal("\u25CF ")
                .withStyle(style -> style.withColor(live ? LIVE_DOT : DEV_DOT))
                .append(Component.translatable(live
                        ? "gui.dungeontrain.builder.profile.target.live"
                        : "gui.dungeontrain.builder.profile.target.dev")
                        .withStyle(style -> style.withColor(0xFFFFFF)));
    }

    /**
     * Point the screen at the other relay and start again from the player's own profile.
     *
     * <p>Not a relist of what is on screen: the two pools hold different builds under different ids,
     * so a selection, a viewed builder and a note about a download all describe rows that do not
     * exist on the other side. Going back to your own profile is the one thing that means the same
     * on both.</p>
     */
    private void toggleTarget() {
        BuilderProfileState.setLive(!BuilderProfileState.live());
        BuilderProfileState.clearCache();
        viewProfile(null);
    }

    /**
     * The header's second line: who these builds belong to.
     *
     * <p>The player's own name stands alone under a title that already says "My Builds"; somebody
     * else's is spelt out, because that is the one case where the title and the list disagree.</p>
     */
    private Component ownerLine() {
        if (viewingOther()) {
            return Component.translatable("gui.dungeontrain.builder.profile.owner_other",
                    viewedName.isEmpty() ? viewedUuid : viewedName);
        }
        return Component.literal(viewedName.isEmpty() ? ownName() : viewedName);
    }

    /**
     * The caption under a tile: what the build is called, and where it currently lives.
     *
     * <p>The name alone: the state used to ride here as a second clause, which made the strip wider
     * than its own cell and said in words what {@link BuilderReviewBadge}'s corner icon and coloured
     * border now say at a glance across the whole grid.</p>
     */
    private Component labelFor(BuilderProfilePacket.Entry entry) {
        return Component.literal(entry.buildName().isEmpty()
                ? "#" + entry.relayId()
                : BuilderLabels.pretty(entry.buildName()));
    }

    /**
     * How this tile is marked: where the build stands with the reviewer, said in a way that survives
     * not reading the caption at all.
     *
     * <p>Only a submittable kind gets one. A portal room or a shell part has no submission state to be
     * in — colouring it would invent a status for something that was never asked about.</p>
     */
    private static BuilderReviewBadge badgeOf(BuilderProfilePacket.Entry entry) {
        return BuilderRelayKinds.canJoinTheTrain(entry.kind())
                ? BuilderReviewBadge.of(entry.review())
                : null;
    }

    /** Which local store to draw this build's tile from — the mirror of {@link BuilderRelayKinds#idOf}. */
    static BuilderPhotoPaths.Kind photoKindOf(BuilderProfilePacket.Entry entry) {
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
    static CarriagePartKind partKindOf(BuilderProfilePacket.Entry entry) {
        return BuilderRelayKinds.PART.equals(entry.kind())
                ? CarriagePartKind.fromId(entry.subKind())
                : null;
    }

    /** As above for a track template. A portal room is stored under its own fixed kind. */
    static TrackKind trackKindOf(BuilderProfilePacket.Entry entry) {
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
        if (builds.isEmpty()) {
            // Three empties now, and each sends the player somewhere different: your own profile with
            // nothing in it needs a save, somebody else's is simply bare, and a filtered list that
            // came up dry needs a different chip rather than a different build.
            return Component.translatable(viewingOther()
                    ? "gui.dungeontrain.builder.profile.empty_other"
                    : "gui.dungeontrain.builder.profile.empty");
        }
        if (shown.isEmpty()) return Component.translatable("gui.dungeontrain.builder.profile.no_matches");
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
