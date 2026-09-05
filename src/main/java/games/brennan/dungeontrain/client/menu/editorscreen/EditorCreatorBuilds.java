package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.BuilderLabels;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import games.brennan.dungeontrain.client.builder.BuilderProfileFilters;
import games.brennan.dungeontrain.client.builder.BuilderProfileState;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.BuilderCreatorResultsPacket;
import games.brennan.dungeontrain.net.BuilderFavouritePacket;
import games.brennan.dungeontrain.net.BuilderFavouritesPacket;
import games.brennan.dungeontrain.net.BuilderFavouritesRequestPacket;
import games.brennan.dungeontrain.net.BuilderProfilePacket;
import games.brennan.dungeontrain.net.BuilderProfileRequestPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriagePartKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The relay builds the editor screen is looking at — one player's, or everybody's — and the
 * browser's second mode.
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

    /**
     * The stars, held here rather than on the panel that draws them.
     *
     * <p>One sink for the favourites packet, because two things on this screen read it — the search
     * panel's list of starred builders and the browser's starred builds — and a screen with two
     * listeners for one packet has whichever registered last.</p>
     *
     * <p>The build set is not simply the entries' own {@code favourite()} flag: that flag is stamped
     * per viewer by {@code /carriages/mine}, and the pooled listing is the operator's, which carries
     * no viewer at all. So a profile listing is allowed to correct this set and the pooled one never
     * is — otherwise opening <b>All builders</b> would read as every star having been lost.</p>
     */
    private static final Set<Integer> STARRED_BUILDS = new HashSet<>();
    private static final Set<String> STARRED_BUILDERS = new HashSet<>();

    /** The starred builders as rows, in the shape a search result has — see {@link #starredBuilders()}. */
    private static List<BuilderCreatorResultsPacket.Creator> starredBuilderRows = List.of();

    /**
     * Builds this session has brought down, and where each one landed.
     *
     * <p>Kept past a change of builder, because the template it wrote is still on disk: coming back
     * to a profile should still say which of its builds are already here rather than offering to
     * load them a second time.</p>
     */
    private static final Map<Integer, Landed> LANDED = new HashMap<>();

    /** Where a downloaded build ended up — enough to send the player to it. */
    public record Landed(BuilderPhotoPaths.Kind kind, String id, String subKind) {}

    private EditorCreatorBuilds() {}

    /** Whether the browser is showing relay builds rather than the editor roster. */
    public static boolean active() {
        return !uuid.isEmpty();
    }

    /** Whether what is loaded is the pooled listing rather than one person's profile. */
    public static boolean pooled() {
        return BuilderProfileRequestPacket.ALL.equals(uuid);
    }

    public static String viewedName() {
        return name;
    }

    /**
     * Whose build this row is — its own owner, falling back to the builder being viewed.
     *
     * <p>Load-bearing since the pooled listing exists: {@link #viewedUuid()} names one person, and in
     * a grid spanning owners it names the wrong one for all but a few rows. Everything addressed at a
     * particular build — its download, its preview, its history — goes through here.</p>
     */
    public static String ownerOf(BuilderProfilePacket.Entry entry) {
        if (entry != null && !entry.ownerUuid().isEmpty()) return entry.ownerUuid();
        return pooled() ? "" : uuid;
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
        // being looked at rather than each holding its own idea — except for the pool, which is
        // nobody's profile and so not a builder that screen could open.
        BuilderProfileState.setViewed(pooled() ? "" : uuid, pooled() ? "" : name);
        if (pooled()) {
            DungeonTrainNet.sendToServer(BuilderProfileRequestPacket.all(BuilderProfileState.live()));
        } else if (!uuid.isEmpty()) {
            DungeonTrainNet.sendToServer(new BuilderProfileRequestPacket(uuid, BuilderProfileState.live()));
        }
    }

    /**
     * Every builder's builds at once, pooled into the browser grid.
     *
     * <p>The reviewer's way in: the search panel asks for a name, and until somebody has one in mind
     * there is nothing to look at. Held under the {@link BuilderProfileRequestPacket#ALL} sentinel so
     * every path that already asks "whose builds are these" keeps working, including the check that
     * drops an answer about somebody else.</p>
     */
    public static void showAll() {
        show(BuilderProfileRequestPacket.ALL, "");
    }

    /**
     * Ask again for whatever is loaded, keeping the selection.
     *
     * <p>What {@link #show} does without throwing the list away: a submit changes one row on the
     * relay, and the answer to "what did that actually do" is the listing this screen is already
     * showing rather than a different one.</p>
     */
    public static void refresh() {
        if (pooled()) {
            DungeonTrainNet.sendToServer(BuilderProfileRequestPacket.all(BuilderProfileState.live()));
        } else if (active()) {
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
        BuilderProfileState.listenForFavourites(EditorCreatorBuilds::onFavourites);
        BuilderFavouritesPacket cached = BuilderProfileState.favourites();
        if (cached != null) onFavourites(cached);
        requestFavourites();
    }

    public static void detach() {
        BuilderProfileState.listen(null);
        BuilderProfileState.listenForFavourites(null);
    }

    /** Ask for this player's stars. Cheap, and the same answer for every list on this screen. */
    public static void requestFavourites() {
        DungeonTrainNet.sendToServer(new BuilderFavouritesRequestPacket(BuilderProfileState.live()));
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
        // A profile listing's rows carry this viewer's own stars, so they are the truth about the
        // builds on it. The pooled listing carries none, and letting it speak would clear the lot.
        if (!pooled()) {
            for (BuilderProfilePacket.Entry entry : builds) {
                if (entry.favourite()) STARRED_BUILDS.add(entry.relayId());
                else STARRED_BUILDS.remove(entry.relayId());
            }
        }
    }

    /** The favourites list arrived: the stars for both of this screen's lists. */
    private static void onFavourites(BuilderFavouritesPacket packet) {
        STARRED_BUILDS.clear();
        for (BuilderProfilePacket.Entry entry : packet.builds()) STARRED_BUILDS.add(entry.relayId());
        STARRED_BUILDERS.clear();
        List<BuilderCreatorResultsPacket.Creator> rows = new ArrayList<>(packet.builders().size());
        for (BuilderFavouritesPacket.Builder b : packet.builders()) {
            STARRED_BUILDERS.add(b.uuid());
            rows.add(new BuilderCreatorResultsPacket.Creator(b.uuid(), b.name(), b.builds()));
        }
        starredBuilderRows = List.copyOf(rows);
    }

    /** Whether this build is starred. */
    public static boolean starred(BuilderProfilePacket.Entry entry) {
        return entry != null && STARRED_BUILDS.contains(entry.relayId());
    }

    public static boolean starredBuilder(String builderUuid) {
        return builderUuid != null && STARRED_BUILDERS.contains(builderUuid);
    }

    /** The starred builders, as the rows an empty search box lists. */
    public static List<BuilderCreatorResultsPacket.Creator> starredBuilders() {
        return starredBuilderRows;
    }

    /**
     * Star or un-star one build.
     *
     * <p>Optimistic, like every other star here: the glyph flips now and the packet follows. Being
     * wrong costs a stale star until the next listing, where waiting on a round trip through the
     * server to the relay costs every press feeling broken.</p>
     */
    public static void toggleStar(BuilderProfilePacket.Entry entry) {
        if (entry == null) return;
        boolean next = !STARRED_BUILDS.contains(entry.relayId());
        if (next) STARRED_BUILDS.add(entry.relayId());
        else STARRED_BUILDS.remove(entry.relayId());
        DungeonTrainNet.sendToServer(
            BuilderFavouritePacket.forBuild(entry.relayId(), next, BuilderProfileState.live()));
        // My Builds caches the same rows; telling it keeps the two screens agreeing inside a session.
        BuilderProfileState.noteFavourite(entry.relayId(), next);
    }

    /** As above for a BUILDER, which is what the search panel's star sets. */
    public static void toggleBuilderStar(BuilderCreatorResultsPacket.Creator creator) {
        if (creator == null) return;
        boolean next = !STARRED_BUILDERS.contains(creator.uuid());
        if (next) STARRED_BUILDERS.add(creator.uuid());
        else STARRED_BUILDERS.remove(creator.uuid());
        DungeonTrainNet.sendToServer(
            BuilderFavouritePacket.forBuilder(creator.uuid(), next, BuilderProfileState.live()));
        // The empty-box list IS the starred builders, so un-starring there removes the row rather than
        // leaving a hollow star sitting in a list of things that are supposed to be starred.
        if (!next) {
            starredBuilderRows = starredBuilderRows.stream()
                .filter(c -> !c.uuid().equals(creator.uuid())).toList();
        } else if (starredBuilderRows.stream().noneMatch(c -> c.uuid().equals(creator.uuid()))) {
            List<BuilderCreatorResultsPacket.Creator> grown = new ArrayList<>(starredBuilderRows);
            grown.add(creator);
            starredBuilderRows = List.copyOf(grown);
        }
    }

    /** Remember that this build is now a template here, and where. */
    static void landed(int relayId, String kindId, String id, String subKind) {
        BuilderPhotoPaths.Kind kind = BuilderPhotoPaths.Kind.fromId(kindId).orElse(null);
        if (kind == null || relayId <= 0) return;
        LANDED.put(relayId, new Landed(kind, id, subKind == null ? "" : subKind));
    }

    /** Where this build landed when it was loaded, or null when it has not been. */
    static Landed landedBuild(int relayId) {
        return LANDED.get(relayId);
    }

    /**
     * Where this build already is in the editor, or null when the editor has never seen it.
     *
     * <p>Two ways it can be here, and the second is the one that was missing: this session loaded
     * it, or the library already holds a template of that kind under that name. The second is what
     * the download path calls {@code ALREADY_HERE} — the editor has room for one template per name,
     * so a name in use IS the build as far as this screen can address it, whoever authored the file.
     * Offering Load again there only earns the refusal a second time.</p>
     */
    static Landed here(EditorRosterIndex index, BuilderProfilePacket.Entry entry) {
        if (entry == null) return null;
        Landed loaded = LANDED.get(entry.relayId());
        if (loaded != null) return loaded;
        PlotCategory category = categoryOf(entry.kind());
        if (category == null || entry.buildName().isEmpty() || index == null) return null;
        for (EditorRosterIndex.Tile tile : index.allTiles()) {
            VariantKey key = tile.key();
            if (key.category() == category && key.displayName().equalsIgnoreCase(entry.buildName())) {
                return new Landed(photoKindOf(entry), entry.buildName(), entry.subKind());
            }
        }
        return null;
    }

    /** The editor category a relay kind is edited in, or null when the editor holds no such thing. */
    static PlotCategory categoryOf(String kind) {
        return switch (kind) {
            case BuilderRelayKinds.CARRIAGE -> PlotCategory.CARRIAGES;
            case BuilderRelayKinds.CONTENTS -> PlotCategory.CONTENTS;
            case BuilderRelayKinds.PART -> PlotCategory.PARTS;
            case BuilderRelayKinds.TRACK -> PlotCategory.TRACKS;
            case BuilderRelayKinds.PORTAL_ROOM -> PlotCategory.PORTALS;
            // A carriage group is authored in the Train Builder; no editor plot holds one.
            default -> null;
        };
    }

    /** Which store this build's kind belongs to — the same mapping {@link #artOf} looks it up by. */
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

    /** Everything loaded, narrowed to the tab and the filter box. */
    public static List<BuilderProfilePacket.Entry> forPage(EditorScreenPage page, String text) {
        return forPage(page, text, BuilderProfileFilters.ALL, false);
    }

    /**
     * As above, narrowed by the two chips creator mode adds: where a build stands with a reviewer,
     * and whether this player has starred it.
     *
     * <p>The review axis goes through {@link BuilderProfileFilters}, which is what My Builds narrows
     * by, so an unknown state files under "not submitted" in both places instead of vanishing from
     * every filter including All. The star axis is answered from {@link #starred} rather than from the
     * row's own flag — the pooled listing has no viewer to carry one.</p>
     */
    public static List<BuilderProfilePacket.Entry> forPage(EditorScreenPage page, String text,
                                                           String review, boolean starredOnly) {
        String needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        List<BuilderProfilePacket.Entry> out = new ArrayList<>();
        for (BuilderProfilePacket.Entry entry : builds) {
            if (!admits(page, entry.kind())) continue;
            if (!needle.isEmpty() && !entry.buildName().toLowerCase(Locale.ROOT).contains(needle)) continue;
            if (!BuilderProfileFilters.matches(entry, BuilderProfileFilters.ALL, review)) continue;
            if (starredOnly && !starred(entry)) continue;
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
