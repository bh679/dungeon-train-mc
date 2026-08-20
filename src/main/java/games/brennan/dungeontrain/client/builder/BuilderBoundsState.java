package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderBounds;
import games.brennan.dungeontrain.builder.BuilderMirrorFlags;
import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderNewOptions;
import games.brennan.dungeontrain.builder.BuilderOpenRequest;
import games.brennan.dungeontrain.builder.structure.BuilderStructure;
import games.brennan.dungeontrain.builder.structure.BuilderStructureMode;
import games.brennan.dungeontrain.builder.structure.BuilderStructureRefresh;
import games.brennan.dungeontrain.builder.structure.BuilderStructureNeeds;
import games.brennan.dungeontrain.net.BuilderBoundsPacket;
import games.brennan.dungeontrain.portal.PortalRoomMode;
import games.brennan.dungeontrain.portal.PortalRoomSettings;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Client-side cache of the Train Builder build volumes, as sent by
 * {@code BuilderBoundsPacket}.
 *
 * <p>Same shape as {@code EditorStatusHudOverlay}: a volatile snapshot written by the network
 * thread and read by the renderer, replaced wholesale rather than mutated so a render pass can
 * never see a half-updated list. Cleared on logout so one world's bounds can't wash blocks in
 * the next.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderBoundsState {

    private static volatile List<BoundingBox> volumes = List.of();
    /** Which builder mode this world was created for; empty outside a builder world. */
    private static volatile String modeId = "";
    /** What this build saves as; empty means an unnamed draft with nothing on disk yet. */
    private static volatile String buildName = "";
    /** Packed mirror flags for this build — what lights the X/Y/Z/V cells in the pause menu. */
    private static volatile BuilderMirrorFlags mirror = BuilderMirrorFlags.NONE;

    /** What the build is and what it's for — everything the info panel reads. */
    private static volatile String subTypeId = "";
    private static volatile String partKindId = "";
    private static volatile String stageId = "";
    /** Which track-side kind is on the plot; empty for a carriage build. */
    private static volatile String trackKindId = "";
    /** Pick weight of the saved template; negative when it doesn't apply (a draft, or a non-carriage). */
    private static volatile int weight = -1;

    /** What this world does with the structures around the build — the pause-menu control's state. */
    private static volatile BuilderStructureMode structureMode = BuilderStructureMode.DEFAULT;
    /** When those structures re-read the template they are made of — the second control's state. */
    private static volatile BuilderStructureRefresh structureRefresh = BuilderStructureRefresh.DEFAULT;
    /** Carriages actually on the track, which decides whether the rest of the group is declared. */
    private static volatile int parked = 0;
    /** The variant a neighbouring slot, and the shell you stand in, are made of. */
    private static volatile String variantId = "";
    /** This world's carriage dimensions, rather than an assumption that they are the default ones. */
    private static volatile CarriageDims dims = CarriageDims.DEFAULT;

    private BuilderBoundsState() {}

    public static void set(BuilderBoundsPacket packet) {
        volumes = List.copyOf(packet.volumes());
        modeId = orEmpty(packet.modeId());
        buildName = orEmpty(packet.buildName());
        mirror = BuilderMirrorFlags.unpack(packet.mirrorMask());
        subTypeId = orEmpty(packet.subTypeId());
        partKindId = orEmpty(packet.partKindId());
        stageId = orEmpty(packet.stageId());
        trackKindId = orEmpty(packet.trackKindId());
        weight = packet.weight();
        structureMode = BuilderStructureMode.orDefault(packet.structureModeId());
        structureRefresh = BuilderStructureRefresh.orDefault(packet.structureRefreshId());
        parked = packet.parked();
        variantId = orEmpty(packet.variantId());
        dims = packet.dims();

        // The ghosts are drawn rather than meshed, so this is all it takes — no chunk rebuild, and
        // nothing to keep in step with the world's own geometry.
        ClientTrackGhost.update(trackKindId, volumes);
    }

    public static String subTypeId() {
        return subTypeId;
    }

    public static String partKindId() {
        return partKindId;
    }

    public static String stageId() {
        return stageId;
    }

    public static String trackKindId() {
        return trackKindId;
    }

    public static int weight() {
        return weight;
    }

    /** What the pause-menu structure control is showing — the server's answer, never a local guess. */
    public static BuilderStructureMode structureMode() {
        return structureMode;
    }

    /** What the pause-menu refresh control is showing — the server's answer, never a local guess. */
    public static BuilderStructureRefresh structureRefresh() {
        return structureRefresh;
    }

    public static CarriageDims dims() {
        return dims;
    }

    /**
     * The structures around this build, worked out from what the server has already told us.
     *
     * <p>Computed here rather than sent: {@code BuilderStructureNeeds} is a pure function of fields
     * that all arrive on {@code BuilderBoundsPacket}, and both sides running the same function is
     * what makes them agree without a third packet to keep in step.</p>
     *
     * <p>The room's mode and size are the two the packet does not carry, for opposite reasons. The
     * size <em>is</em> the build volume — reading it off the box the server already sent is one
     * source of truth rather than two. The mode comes from the room's own {@code weights.json} tag,
     * which is a server-side store this can reach because a builder world is single-player by
     * construction — the same thing the Open grid already relies on.</p>
     */
    public static List<BuilderStructure.Placement> structures() {
        BuilderStructureNeeds.Context ctx = context();
        if (ctx == null || !structureMode.draws() && !structureMode.stamps()) {
            return List.of();
        }
        return BuilderStructureNeeds.around(ctx);
    }

    /**
     * This build, as the declaration table wants to be asked about it — or null outside one.
     *
     * <p>Pulled out of {@link #structures} because the resolver's inputs need it too: which stored
     * template the open build <em>is</em> is a question about the same fields, and answering it from
     * a second hand-assembled context is how the two would come to disagree.</p>
     */
    public static BuilderStructureNeeds.Context context() {
        BuilderMode mode = BuilderMode.fromId(modeId).orElse(null);
        if (mode == null) {
            return null;
        }
        boolean room = isRoomOpen();
        return new BuilderStructureNeeds.Context(
                mode,
                BuilderNewOptions.SubType.fromId(subTypeId).orElse(null),
                TrackKind.fromId(trackKindId),
                buildName, dims, parked,
                room ? roomMode() : null,
                room && !volumes.isEmpty() ? BuilderBounds.sizeOf(volumes.get(0)) : null,
                variantId);
    }

    /**
     * Whether what is open is a portal room.
     *
     * <p>The one question that decides whether anything needs the build's <em>live</em> blocks: a
     * room's copies are copies of the room you are building, and nothing else around any other build
     * is made of the build.</p>
     */
    public static boolean isRoomOpen() {
        return BuilderOpenRequest.PORTAL_ROOM_SUB_TYPE.equals(subTypeId);
    }

    /** The authored mode for the open room, defaulting the way every other reader of the tag does. */
    private static PortalRoomMode roomMode() {
        if (buildName.isEmpty()) {
            return null;
        }
        try {
            return PortalRoomSettings.of(buildName).mode();
        } catch (RuntimeException e) {
            // The registry is a server-side store; if this ever runs where there isn't one, no
            // structures is a far better outcome than a crashed render thread.
            return PortalRoomMode.DEFAULT;
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** What the build saves as, or empty for an unnamed draft. */
    public static String buildName() {
        return buildName;
    }

    /** Mirror setting for the current build. */
    public static BuilderMirrorFlags mirror() {
        return mirror;
    }

    /**
     * Set the mirror flags without waiting for the server.
     *
     * <p>For {@link BuilderMirrorButton}, which stays open after a click and so has to show the
     * new state before the round-trip lands. The next {@code BuilderBoundsPacket} — the server
     * sends one after every mirror toggle — replaces this with the authoritative value.</p>
     */
    public static void setMirror(BuilderMirrorFlags flags) {
        mirror = flags == null ? BuilderMirrorFlags.NONE : flags;
    }

    /** True when the current build has no template yet — Save has to ask for a name. */
    public static boolean isDraft() {
        return buildName.isEmpty();
    }

    /** Mode id, or empty when the server hasn't said (or this isn't a builder world). */
    public static String modeId() {
        return modeId;
    }

    public static List<BoundingBox> volumes() {
        return volumes;
    }

    public static void clear() {
        volumes = List.of();
        modeId = "";
        buildName = "";
        mirror = BuilderMirrorFlags.NONE;
        subTypeId = "";
        partKindId = "";
        stageId = "";
        trackKindId = "";
        weight = -1;
        structureMode = BuilderStructureMode.DEFAULT;
        structureRefresh = BuilderStructureRefresh.DEFAULT;
        parked = 0;
        variantId = "";
        dims = CarriageDims.DEFAULT;
        ClientTrackGhost.clear();
    }
}
