package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.BuilderNewOptions;
import games.brennan.dungeontrain.builder.relay.BuilderRelayDownload;
import games.brennan.dungeontrain.builder.relay.BuilderRelayInstall;
import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.client.builder.BuildRenderCapture;
import games.brennan.dungeontrain.client.builder.BuilderProfilePrefabConflictScreen;
import games.brennan.dungeontrain.client.builder.BuilderProfileScreen;
import games.brennan.dungeontrain.client.builder.BuilderProfileState;
import games.brennan.dungeontrain.client.builder.BuilderSubmitNoteScreen;
import games.brennan.dungeontrain.client.builder.RelayBuildPreviews;
import games.brennan.dungeontrain.client.menu.CreatorParentPickerScreen;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.BuilderProfileActionPacket;
import games.brennan.dungeontrain.net.BuilderProfileDownloadPacket;
import games.brennan.dungeontrain.net.BuilderProfileDownloadResultPacket;
import games.brennan.dungeontrain.net.BuilderProfilePacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.WorkbenchAutoloadPacket;
import games.brennan.dungeontrain.net.WorkbenchStagePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The X menu's creator-build side: bringing a relay build down into this install, staging it on
 * the Workbench, submitting it to the train, walking to it once it has landed, and paging the
 * previewer through a build's recorded versions.
 *
 * <p>Everything here is a press and the answer it waits for. The screen owns the panes and the
 * modal host; this owns the state between a press and its answer — the note under the preview,
 * the greyed Load button, the walk under way — and the timeouts that hand the buttons back when
 * an answer is lost.</p>
 */
final class EditorCreatorLoadController {

    /** What a host screen gives the controller: a modal host, the roster refresh, and the way out. */
    interface Host {
        EditorModalHost modal();

        /** A command went out: give the server a moment, then ask what changed. */
        void afterCommand();

        /** Close the screen — the walk arrived. */
        void close();

        /** The screen itself, as the parent a conflict screen comes back to. */
        Screen screen();
    }

    static final int GOING_TIMEOUT_TICKS = EditorGuiScreen.GOING_TIMEOUT_TICKS;
    static final int LOAD_TIMEOUT_TICKS = EditorGuiScreen.LOAD_TIMEOUT_TICKS;
    private static final int SUBMIT_REFRESH_TICKS = 20;

    private final Host host;

    /** What the last Load said about the selected build, under its preview. */
    private String creatorNote;
    /** The last Load answered "name taken": the next press loads a copy instead. */
    private boolean loadAsCopy;
    private List<String> takenNames = List.of();
    /** The build a Go here is walking to, while the walk is under way. */
    private EditorCreatorBuilds.Landed goingTo;
    private int goingTicks;
    /** A Load is out and unanswered: the button is withheld until the answer or the timeout. */
    private boolean loading;
    private int loadingTicks;
    /** Ticks until the listing is asked again after a submit / withdraw. */
    private int submitTicks;
    /** The press most recently sent, so the loot-prefab question can replay it with the answer attached. */
    private BuilderProfileDownloadPacket lastDownload;
    /** The relay row the last "To Workbench" press asked about, or -1; its answer comes back as a download result. */
    private int lastStageRelayId = -1;
    /** The relay rows an Autoload is still waiting on; each answer comes back as a download result. */
    private final Set<Integer> autoloadPending = new HashSet<>();
    private int autoloadTotal;
    /** The recorded version the previewer shows (0 = the build as it is now), and whose. */
    private int previewSeq;
    private int previewRelayId;

    EditorCreatorLoadController(Host host) {
        this.host = host;
    }

    // ------------------------------------------------------------------ what the panes read

    String note() {
        return creatorNote;
    }

    boolean loadAsCopy() {
        return loadAsCopy;
    }

    boolean going() {
        return goingTo != null;
    }

    boolean loading() {
        return loading;
    }

    int previewSeq() {
        return previewSeq;
    }

    /** The builder's upload the browser has selected, or null. */
    static BuilderProfilePacket.Entry selected() {
        return EditorCreatorBuilds.byId(EditorCreatorBuilds.selectedId());
    }

    // ------------------------------------------------------------------ ticks

    void tick() {
        tickWalk();
        tickLoad();
        if (submitTicks > 0 && --submitTicks == 0) {
            EditorCreatorBuilds.refresh();
        }
    }

    /**
     * Watch a walk that is under way: close the screen the moment the player is standing in the
     * build, and hand the button back if they never arrive.
     *
     * <p>Closing is the point of pressing Go here — the build is a plot in the world and the menu is
     * in front of it. Watching where the player actually is, rather than closing on the press,
     * means the menu stays up when the walk did not happen (no permission, a plot that would not
     * stamp) instead of leaving them looking at the wrong place with nothing said.</p>
     */
    private void tickWalk() {
        if (goingTo == null) return;
        if (EditorTemplateJumpBridge.arrived(goingTo.kind(), goingTo.id(), goingTo.subKind())) {
            goingTo = null;
            host.close();
            return;
        }
        if (--goingTicks <= 0) goingTo = null;
    }

    /**
     * Watch a Load that is under way: hand the button back, and say so, if the server never answers.
     *
     * <p>Every server path answers with an outcome — this only fires when that answer was lost (a
     * disconnect, a server that crashed mid-install). Without it the button would stay greyed for
     * the life of the screen with nothing to say why.</p>
     */
    private void tickLoad() {
        if (!loading) return;
        if (--loadingTicks <= 0) {
            loading = false;
            // Given up on: an answer that turns up after this is dropped rather than replayed.
            lastDownload = null;
            lastStageRelayId = -1;
            autoloadPending.clear();
            creatorNote = EditorScreenLang.text(EditorScreenLang.CREATOR_LOAD_FAILED);
        }
    }

    // ------------------------------------------------------------------ presses

    /**
     * Bring the selected relay build down into this install's library.
     *
     * <p>The same download My Builds runs, and the reason a preview is not the end of the story: a
     * mesh can be turned around in the pane, but only a template on disk can be stood in, tested and
     * ridden. Once it lands the roster picks it up like any other template.</p>
     *
     * <p>A first press asks for the build under its own name. If that name is already spoken for
     * here, the press after it loads a copy instead — the build is somebody else's work and this
     * screen will not write over whatever is already wearing the name.</p>
     */
    void loadSelected() {
        BuilderProfilePacket.Entry entry = selected();
        // One fetch at a time: the pane withholds the button while a Load is out, and nothing else
        // should get to send the same build down twice either.
        if (entry == null || loading) return;
        boolean live = BuilderProfileState.live();
        // The row's own owner, not the builder being viewed: the pooled listing spans them.
        String owner = EditorCreatorBuilds.ownerOf(entry);
        // The name this screen has been showing over their builds, so the install can caption the
        // template with whose work it is — the fetch itself never answers that. In the pooled
        // listing the viewed name is nobody's, so the row's own is what carries it.
        String ownerName = ownerNameOf(entry);
        // Where it lands in the roster: under the chosen variant parent for the kinds that have
        // sub-variants, at top level (blank) for the rest — carriages have no parents to land under.
        String parent = CreatorLoadParent.supports(entry.kind())
            ? CreatorLoadParent.parentFor(EditorCreatorBuilds.categoryOf(entry.kind())) : "";
        // A copy when the last answer said the name is taken — or when this screen can already see
        // the build is here (Shift on the loaded slot): asking AS_IS would only earn that answer.
        boolean copy = loadAsCopy || EditorCreatorBuilds.here(EditorRosterClient.index(), entry) != null;
        sendDownload(copy
            ? new BuilderProfileDownloadPacket(entry.relayId(), BuilderRelayInstall.Resolution.LOAD_AS_NEW,
                BuilderNewOptions.firstFreeName(entry.buildName(), takenNames), owner, ownerName, live, false,
                parent)
            : new BuilderProfileDownloadPacket(entry.relayId(), BuilderRelayInstall.Resolution.AS_IS, "",
                owner, ownerName, live, false, parent));
    }

    private static String ownerNameOf(BuilderProfilePacket.Entry entry) {
        return entry.ownerName() == null || entry.ownerName().isEmpty()
            ? EditorCreatorBuilds.viewedName()
            : entry.ownerName();
    }

    /**
     * Stage every build the grid lists — one server round trip, which enters the Workbench first when
     * another category is resident. Answers arrive one per build and are counted in
     * {@link #onDownloadResult}; the note reads the count up.
     */
    void autoloadWorkbench(List<BuilderProfilePacket.Entry> listed) {
        if (listed.isEmpty() || loading) return;
        List<WorkbenchAutoloadPacket.Item> items = new ArrayList<>();
        autoloadPending.clear();
        for (BuilderProfilePacket.Entry entry : listed) {
            if (items.size() >= WorkbenchAutoloadPacket.MAX_ITEMS) break;
            items.add(new WorkbenchAutoloadPacket.Item(entry.relayId(),
                EditorCreatorBuilds.ownerOf(entry), ownerNameOf(entry)));
            autoloadPending.add(entry.relayId());
        }
        autoloadTotal = items.size();
        lastDownload = null;
        lastStageRelayId = -1;
        DungeonTrainNet.sendToServer(new WorkbenchAutoloadPacket(items, BuilderProfileState.live()));
        creatorNote = EditorScreenLang.text(EditorScreenLang.CREATOR_AUTOLOADING, 0, autoloadTotal);
        loading = true;
        loadingTicks = LOAD_TIMEOUT_TICKS * Math.max(1, autoloadTotal);
    }

    /**
     * Stage the selected build on the Workbench: the same fetch as a Load, landing on the shelf instead
     * of in a store — and never a category switch. What comes back is read by {@link #onDownloadResult}.
     */
    void stageSelected() {
        BuilderProfilePacket.Entry entry = selected();
        if (entry == null || loading) return;
        lastDownload = null;
        lastStageRelayId = entry.relayId();
        DungeonTrainNet.sendToServer(new WorkbenchStagePacket(entry.relayId(),
            EditorCreatorBuilds.ownerOf(entry), ownerNameOf(entry), BuilderProfileState.live()));
        creatorNote = EditorScreenLang.text(EditorScreenLang.CREATOR_LOADING_BUILD);
        loading = true;
        loadingTicks = LOAD_TIMEOUT_TICKS;
    }

    private void sendDownload(BuilderProfileDownloadPacket packet) {
        this.lastDownload = packet;
        DungeonTrainNet.sendToServer(packet);
        creatorNote = EditorScreenLang.text(EditorScreenLang.CREATOR_LOADING_BUILD);
        loading = true;
        loadingTicks = LOAD_TIMEOUT_TICKS;
    }

    /**
     * Put the selected build on the train, or take it back off.
     *
     * <p>The same packet My Builds sends, and authorised the same way — by the owner secret in this
     * world's saved data, looked up server-side from the id. Nothing is assumed to have worked: the
     * server re-reads the profile once the relay answers, and the label follows what comes back
     * rather than what was asked for, because the relay can refuse (a build another world is holding).</p>
     */
    void submitSelected() {
        BuilderProfilePacket.Entry entry = selected();
        if (entry == null || !BuilderRelayKinds.canSubmitForReview(entry.kind())) return;
        if (entry.published()) {
            sendCreatorAction(new BuilderProfileActionPacket(entry.relayId(), false));
            return;
        }
        // A submit first asks what the reviewer should know; the send happens on that screen's
        // Submit, and its Cancel comes back here with nothing sent.
        BuilderSubmitNoteScreen.open(entry.relayId(), Component.literal(entry.buildName()),
                note -> sendCreatorAction(new BuilderProfileActionPacket(entry.relayId(), true, note,
                    BuildRenderCapture.png(entry))));
    }

    private void sendCreatorAction(BuilderProfileActionPacket packet) {
        DungeonTrainNet.sendToServer(packet);
        // The server's own re-read is addressed to the player's OWN profile, so this listing has to
        // ask again itself — and after a moment, once the relay has actually answered the action.
        creatorNote = EditorScreenLang.text(EditorScreenLang.CREATOR_SUBMITTING);
        submitTicks = SUBMIT_REFRESH_TICKS;
    }

    /**
     * Drop what the last Load said — it was about a build that is no longer the one on screen.
     *
     * <p>Not the button: {@code loading} belongs to the press, not the row. It stays greyed on
     * whichever row is looked at until the answer or the timeout hands it back, so a second build
     * cannot be sent down while the first is still on its way.</p>
     */
    void forgetLastLoad() {
        creatorNote = null;
        loadAsCopy = false;
        takenNames = List.of();
    }

    /** A different build was selected: the walk and the last answer belonged to the one before. */
    void onSelectionChanged() {
        goingTo = null;
        forgetLastLoad();
    }

    /**
     * Walk to the plot the selected build was loaded onto.
     *
     * <p>The header's answer to "it is here now": a category switch when it is not the one being
     * stood in, then the enter command for the template — the same walk My Builds makes after a
     * download, through the same helper.</p>
     */
    void goToLoadedBuild() {
        BuilderProfilePacket.Entry entry = selected();
        EditorCreatorBuilds.Landed landed = EditorCreatorBuilds.here(EditorRosterClient.index(), entry);
        if (landed == null) return;
        if (!EditorTemplateJumpBridge.go(landed.kind(), landed.id(), landed.subKind())) return;
        goingTo = landed;
        goingTicks = GOING_TIMEOUT_TICKS;
        host.afterCommand();
    }

    /** Choose the variant parent the selected build will be filed under when it is loaded. */
    void pickLoadParent() {
        BuilderProfilePacket.Entry entry = selected();
        if (entry == null || !CreatorLoadParent.supports(entry.kind())) return;
        PlotCategory category = EditorCreatorBuilds.categoryOf(entry.kind());
        if (category == null) return;
        host.modal().open(new CreatorParentPickerScreen(category, host.modal()::pop));
    }

    // ------------------------------------------------------------------ answers

    /** A download finished: say what happened, and pick up what landed. */
    void onDownloadResult(BuilderProfileDownloadResultPacket packet) {
        // Only the press this screen is waiting on: an answer to one it never made, or gave up on,
        // says nothing about the row in front of the player.
        if (autoloadPending.remove(packet.relayId())) {
            // One of a batch: count it, and stay greyed until the last one is in.
            int done = autoloadTotal - autoloadPending.size();
            creatorNote = autoloadPending.isEmpty()
                ? EditorScreenLang.text(EditorScreenLang.CREATOR_AUTOLOADED, done)
                : EditorScreenLang.text(EditorScreenLang.CREATOR_AUTOLOADING, done, autoloadTotal);
            if (autoloadPending.isEmpty()) {
                loading = false;
                host.afterCommand();
            }
            return;
        }
        boolean stagePress = lastStageRelayId >= 0 && packet.relayId() == lastStageRelayId;
        if (!stagePress && (lastDownload == null || packet.relayId() != lastDownload.relayId())) return;
        if (stagePress) lastStageRelayId = -1;
        // Answered, whatever the answer: the button is back. A prefab question below re-sends and
        // greys it again.
        loading = false;
        creatorNote = EditorScreenLang.text(BuilderProfileScreen.noteKeyFor(packet.outcome()));
        // The build brought loot prefabs this install already has, with different contents: put both
        // versions in front of the player, and replay the same press with their choices attached.
        if (packet.outcome() == BuilderRelayDownload.Outcome.PREFAB_CONFLICT && lastDownload != null) {
            BuilderProfileDownloadPacket sent = lastDownload;
            Minecraft.getInstance().setScreen(new BuilderProfilePrefabConflictScreen(host.screen(), packet.id(),
                packet.conflicts(), (useTheirs, renames) -> sendDownload(sent.answeringPrefabs(useTheirs, renames))));
            return;
        }
        boolean nameInUse = packet.outcome() == BuilderRelayDownload.Outcome.ALREADY_HERE
            || packet.outcome() == BuilderRelayDownload.Outcome.NAME_TAKEN;
        takenNames = nameInUse ? List.copyOf(packet.takenNames()) : List.of();
        loadAsCopy = nameInUse;
        if (packet.outcome() == BuilderRelayDownload.Outcome.INSTALLED) {
            // Remembered against the build that was asked for — the packet's row, not whichever is
            // selected now: the pane stops offering to load it and offers the walk to it instead.
            EditorCreatorBuilds.landed(packet.relayId(), packet.kindId(), packet.id(), packet.subKind());
            // It is a template now, so the roster has to be asked again before it will show one.
            host.afterCommand();
        }
    }

    // ------------------------------------------------------------------ versions

    /**
     * Keep the previewer's paging pointed at the selected build, and ask the relay what versions it
     * has of it.
     *
     * <p>A change of selection resets to the build as it is now, because a seq belongs to one relay
     * row and means nothing in another's history. The index ask goes out once per build — the cache
     * remembers the answer, including "no versions", so this costs a map lookup a frame after
     * that.</p>
     */
    void trackVersionsOf(int relayId, String ownerUuid) {
        if (relayId != previewRelayId) {
            previewRelayId = relayId;
            previewSeq = 0;
        }
        if (relayId > 0 && RelayBuildPreviews.versions(relayId) == null) {
            // -1: the newest recorded frame, and the index of every seq with it.
            RelayBuildPreviews.request(relayId, ownerUuid, BuilderProfileState.live(), -1);
        }
    }

    /** Step the preview one version older or newer, and fetch it if it is not here yet. */
    void pageVersion(boolean older) {
        int[] seqs = RelayBuildPreviews.versions(previewRelayId);
        if (seqs == null || seqs.length == 0) return;
        int next = older ? VersionStrip.older(seqs, previewSeq) : VersionStrip.newer(seqs, previewSeq);
        if (next == previewSeq) return;
        previewSeq = next;
        if (next != 0) {
            RelayBuildPreviews.request(previewRelayId,
                EditorCreatorBuilds.active() ? EditorCreatorBuilds.ownerOf(selected()) : "",
                BuilderProfileState.live(), next);
        }
    }
}
