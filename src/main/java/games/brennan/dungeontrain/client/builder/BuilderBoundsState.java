package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderMirrorFlags;
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

    private BuilderBoundsState() {}

    public static void set(List<BoundingBox> newVolumes, String newModeId, String newBuildName, int mirrorMask) {
        volumes = List.copyOf(newVolumes);
        modeId = newModeId == null ? "" : newModeId;
        buildName = newBuildName == null ? "" : newBuildName;
        mirror = BuilderMirrorFlags.unpack(mirrorMask);
    }

    /** What the build saves as, or empty for an unnamed draft. */
    public static String buildName() {
        return buildName;
    }

    /** Mirror setting for the current build. */
    public static BuilderMirrorFlags mirror() {
        return mirror;
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
    }
}
