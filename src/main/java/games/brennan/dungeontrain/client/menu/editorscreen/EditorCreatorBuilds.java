package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.BuilderLabels;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import games.brennan.dungeontrain.client.builder.BuilderProfileState;
import games.brennan.dungeontrain.net.BuilderProfilePacket;
import games.brennan.dungeontrain.net.BuilderProfileRequestPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriagePartKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The builds of the player the editor screen is looking at, and the browser's second mode.
 *
 * <p>These are rows on the relay, not templates on this machine: nothing here is in the editor
 * roster, nothing can be renamed, saved or stood in, and the browser says so by offering none of
 * those. What it does offer is the thing a reviewer opens the search for — seeing what somebody
 * uploaded, in the grid they were already looking at.</p>
 *
 * <p>Static, like {@link EditorScreenState}: which builder is loaded outlives a reopen of the
 * screen, exactly as the browser's page and filters do.</p>
 */
public final class EditorCreatorBuilds {

    private static String uuid = "";
    private static String name = "";
    private static List<BuilderProfilePacket.Entry> builds = List.of();
    private static BuilderProfilePacket.Status status;
    private static int selectedId = -1;

    private EditorCreatorBuilds() {}

    /** Whether the browser is showing a builder's uploads rather than the editor roster. */
    public static boolean active() {
        return !uuid.isEmpty();
    }

    public static String viewedName() {
        return name;
    }

    public static String viewedUuid() {
        return uuid;
    }

    /** Null while the first answer is still out — which is not the same as an empty profile. */
    public static BuilderProfilePacket.Status status() {
        return status;
    }

    public static int selectedId() {
        return selectedId;
    }

    public static void select(int relayId) {
        selectedId = relayId;
    }

    /** Load a builder's uploads, dropping whatever the last one had. */
    public static void show(String creatorUuid, String creatorName) {
        uuid = creatorUuid == null ? "" : creatorUuid;
        name = creatorName == null ? "" : creatorName;
        builds = List.of();
        status = null;
        selectedId = -1;
        // Remembered where the pause menu's My Builds reads it too, so the two agree about who is
        // being looked at rather than each holding its own idea.
        BuilderProfileState.setViewed(uuid, name);
        if (!uuid.isEmpty()) {
            DungeonTrainNet.sendToServer(new BuilderProfileRequestPacket(uuid, BuilderProfileState.live()));
        }
    }

    /** Back to the editor roster. */
    public static void clear() {
        show("", "");
    }

    /** Listen for profile answers while the editor screen is open. */
    public static void attach() {
        BuilderProfileState.listen(EditorCreatorBuilds::onProfile);
    }

    public static void detach() {
        BuilderProfileState.listen(null);
    }

    /**
     * A profile arrived.
     *
     * <p>Dropped unless it is about the builder on screen: switching builders while an ask is out
     * leaves two in flight, and nothing promises they come back in the order they were sent.</p>
     */
    private static void onProfile(BuilderProfilePacket packet) {
        if (!active() || !uuid.equals(packet.ownerUuid())) return;
        builds = packet.builds();
        status = packet.status();
        if (name.isEmpty()) name = packet.ownerName();
        if (builds.stream().noneMatch(e -> e.relayId() == selectedId)) selectedId = -1;
    }

    /** Everything loaded, narrowed to the tab and the filter box. */
    public static List<BuilderProfilePacket.Entry> forPage(EditorScreenPage page, String text) {
        String needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        List<BuilderProfilePacket.Entry> out = new ArrayList<>();
        for (BuilderProfilePacket.Entry entry : builds) {
            if (!admits(page, entry.kind())) continue;
            if (!needle.isEmpty() && !entry.buildName().toLowerCase(Locale.ROOT).contains(needle)) continue;
            out.add(entry);
        }
        return List.copyOf(out);
    }

    public static BuilderProfilePacket.Entry byId(int relayId) {
        for (BuilderProfilePacket.Entry entry : builds) {
            if (entry.relayId() == relayId) return entry;
        }
        return null;
    }

    /**
     * Whether a relay kind belongs on a tab.
     *
     * <p>The same grouping the tabs use for local templates — parts browse under Carriages, a
     * portal room under Dimensions — so switching to somebody else's builds does not also switch
     * what a tab means.</p>
     */
    static boolean admits(EditorScreenPage page, String kind) {
        if (page == null || page == EditorScreenPage.ALL) return true;
        return switch (page) {
            case CARRIAGES -> BuilderRelayKinds.CARRIAGE.equals(kind)
                || BuilderRelayKinds.CARRIAGE_GROUP.equals(kind)
                || BuilderRelayKinds.PART.equals(kind);
            case CONTENTS -> BuilderRelayKinds.CONTENTS.equals(kind);
            case TRACKS -> BuilderRelayKinds.TRACK.equals(kind);
            case DIMENSIONS -> BuilderRelayKinds.PORTAL_ROOM.equals(kind);
            default -> true;
        };
    }

    /**
     * Where to look for this build's picture — the same four things that find a local template's.
     *
     * <p>A build uploaded from another machine has no local file, and its tile falls back to the
     * slate the browser draws for anything it cannot picture. One made here draws its own blocks,
     * which is what makes a reviewer's own uploads recognisable in the grid.</p>
     */
    static TemplateArt artOf(BuilderProfilePacket.Entry entry) {
        return switch (entry.kind()) {
            case BuilderRelayKinds.CARRIAGE_GROUP ->
                new TemplateArt(BuilderPhotoPaths.Kind.CARRIAGE_GROUP, entry.buildName(), null, null);
            case BuilderRelayKinds.CONTENTS ->
                new TemplateArt(BuilderPhotoPaths.Kind.CONTENTS, entry.buildName(), null, null);
            case BuilderRelayKinds.PART -> {
                CarriagePartKind pk = CarriagePartKind.fromId(entry.subKind());
                yield pk == null ? null
                    : new TemplateArt(BuilderPhotoPaths.Kind.PART, entry.buildName(), pk, null);
            }
            case BuilderRelayKinds.TRACK -> {
                TrackKind tk = TrackKind.fromId(entry.subKind());
                yield tk == null ? null
                    : new TemplateArt(BuilderPhotoPaths.Kind.TRACK, entry.buildName(), null, tk);
            }
            case BuilderRelayKinds.PORTAL_ROOM ->
                new TemplateArt(BuilderPhotoPaths.Kind.PORTAL_ROOM, entry.buildName(), null, TrackKind.PORTAL_ROOM);
            default -> new TemplateArt(BuilderPhotoPaths.Kind.CARRIAGE, entry.buildName(), null, null);
        };
    }

    /** What a tile is called: the build's name, as the builder screens spell it. */
    static String label(BuilderProfilePacket.Entry entry) {
        return entry.buildName().isEmpty()
            ? "#" + entry.relayId() : BuilderLabels.pretty(entry.buildName());
    }

    /** The lang key naming this build's kind, for the detail pane. */
    static String kindKey(String kind) {
        return switch (kind) {
            case BuilderRelayKinds.CARRIAGE_GROUP -> "gui.dungeontrain.builder.profile.type.carriage_group";
            case BuilderRelayKinds.CONTENTS -> "gui.dungeontrain.builder.profile.type.contents";
            case BuilderRelayKinds.PART -> "gui.dungeontrain.builder.profile.type.part";
            case BuilderRelayKinds.TRACK -> "gui.dungeontrain.builder.profile.type.track";
            case BuilderRelayKinds.PORTAL_ROOM -> "gui.dungeontrain.builder.profile.type.portal_room";
            default -> "gui.dungeontrain.builder.profile.type.carriage";
        };
    }

    /** Where this build stands with a reviewer, as the My Builds chips word it. */
    static String reviewKey(String review) {
        return switch (BuilderReviewState.of(review)) {
            case BuilderReviewState.SUBMITTED -> "gui.dungeontrain.builder.profile.status.submitted";
            case BuilderReviewState.ACCEPTED -> "gui.dungeontrain.builder.profile.status.accepted";
            case BuilderReviewState.DECLINED -> "gui.dungeontrain.builder.profile.status.declined";
            default -> "gui.dungeontrain.builder.profile.status.none";
        };
    }
}
