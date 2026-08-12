package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderMirrorFlags;
import games.brennan.dungeontrain.net.BuilderBoundsPacket;
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
        ClientTrackGhost.clear();
    }
}
