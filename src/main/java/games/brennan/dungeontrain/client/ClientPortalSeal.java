package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.portal.PortalSealPlane;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * The seal cut in force this frame — the client half of {@link PortalSealPlane}, and the one thing
 * the renderer hooks read.
 *
 * <p><b>A place, not a state</b>, like {@link ClientPortalRoomDepth} and the other portal caches: it
 * is recomputed from the camera every frame rather than switched on by a message, so walking out of a
 * twin ends the cut with nothing re-sent and a disconnect cannot strand a client rendering half a
 * world. Nothing here is synced at all — {@link ClientUpsideDownBand} already carries this world's
 * terrain floor from the join packet, and the lid follows from it and COMMON config.</p>
 *
 * <p><b>Once a frame, not once a call.</b> {@code Frustum#isVisible} runs thousands of times per
 * frame; deciding the cut there would put a config lookup and the lid arithmetic on the hottest path
 * in the renderer. {@link #beginFrame} does it once from the camera event, and every reader after
 * that is one comparison against a record.</p>
 */
public final class ClientPortalSeal {

    /** Volatile: written on the render thread, read from chunk-build threads through the frustum. */
    private static volatile PortalSealPlane.Cut cut = PortalSealPlane.Cut.NONE;

    private ClientPortalSeal() {}

    /**
     * Re-evaluate the cut for this frame's camera. Called from {@code PortalSealEvents} before the
     * level is drawn, so the frame a portal swap lands on is already cut correctly.
     */
    public static void beginFrame(double cameraX, double cameraY) {
        if (!ClientDisplayConfig.isPortalTwinSealCulling() || !inTheSyncedWorld()) {
            cut = PortalSealPlane.Cut.NONE;
            return;
        }
        cut = PortalSealPlane.cutFor(
            ClientUpsideDownBand.bedrockY(),
            ClientUpsideDownBand.roofY(),
            ClientUpsideDownBand.isInBand(Mth.floor(cameraX)),
            cameraY);
    }

    /**
     * Whether the camera is in the world the synced planes actually describe — the overworld.
     *
     * <p><b>Load-bearing, not a tidiness check.</b> {@code bedrockY} is measured off the
     * <i>overworld's</i> chunk generator and sent once on login ({@code VoidBandSyncPacket}), so it
     * says nothing about any other dimension. The Train Builder's dimension starts at Y 0 and stands
     * its plots on that floor; an overworld terrain floor of 32 applied there would cut the entire
     * build away. The other portal client caches self-disable outside the overworld for the same
     * reason — see {@code VoidSkyEvents} and {@code UpsideDownFogEvents}.</p>
     */
    private static boolean inTheSyncedWorld() {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && level.dimension().equals(Level.OVERWORLD);
    }

    /** Forget the cut. Wired to logging out, so a seal never leaks into the next world. */
    public static void reset() {
        cut = PortalSealPlane.Cut.NONE;
    }

    /** The cut in force this frame, for the arrival trace — which of the two cuts, not just whether. */
    public static PortalSealPlane.Cut cut() {
        return cut;
    }

    /** Whether anything is being hidden at all — the early-out every hook opens with. */
    public static boolean sealed() {
        return cut.sealed();
    }

    /** Whether a box spanning {@code [minY, maxY]} is wholly on the hidden side of this frame's cut. */
    public static boolean hides(double minY, double maxY) {
        return cut.hides(minY, maxY);
    }
}
