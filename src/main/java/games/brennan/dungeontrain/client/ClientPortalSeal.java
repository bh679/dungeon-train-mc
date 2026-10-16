package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.portal.PortalSealPlane;
import games.brennan.dungeontrain.worldgen.legacy.preset.AmplifiedDrop;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.SectionPos;
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

    /**
     * Camera column of this frame, in blocks, and how far from it a block may be trimmed. Volatile
     * for the same reason as {@link #cut}; the three are read together only by {@link #hidesBlock}.
     */
    private static volatile int trimCentreX;
    private static volatile int trimCentreZ;
    private static volatile int trimReach;

    /**
     * Blocks beyond the render distance a trim still reaches — slack for the section the camera
     * stands in and for sections meshed a frame late. Sable sub-levels sit in plots millions of
     * blocks out, so any finite reach keeps their meshes out of the trim; their plot Y is a world Y.
     */
    private static final int TRIM_REACH_SLACK = 32;

    private ClientPortalSeal() {}

    /**
     * Re-evaluate the cut for this frame's camera. Called from {@code PortalSealEvents} before the
     * level is drawn, so the frame a portal swap lands on is already cut correctly.
     */
    public static void beginFrame(double cameraX, double cameraY, double cameraZ) {
        PortalSealPlane.Cut before = cut;
        trimCentreX = Mth.floor(cameraX);
        trimCentreZ = Mth.floor(cameraZ);
        trimReach = viewReach() + TRIM_REACH_SLACK;
        cut = computeCut(cameraX, cameraY, cameraZ);
        if (!sameTrim(before, cut)) rebuildTrimmedSections(before, cut, cameraX, cameraZ);
    }

    private static PortalSealPlane.Cut computeCut(double cameraX, double cameraY, double cameraZ) {
        if (!ClientDisplayConfig.isPortalTwinSealCulling() || !inTheSyncedWorld()) {
            return PortalSealPlane.Cut.NONE;
        }
        int camX = Mth.floor(cameraX);
        AmplifiedDrop drop = ClientUpsideDownBand.amplifiedDrop();
        if (drop != null && ClientUpsideDownBand.isSunkZoneWithin(camX, viewReach())) {
            return PortalSealPlane.cutForAmplified(drop.floorY(ClientUpsideDownBand.bedrockY()), drop.lidY(),
                ClientUpsideDownBand.isInSunkZone(camX), cameraY);
        }
        return PortalSealPlane.cutFor(
            ClientUpsideDownBand.bedrockY(),
            ClientUpsideDownBand.roofY(),
            atticCanBeHere(camX, cameraX, cameraY, cameraZ),
            cameraY);
    }

    /**
     * Whether a camera here can be in an attic: inside the band, where the lid is stamped — or inside
     * a dimensional carriage, wherever it is.
     *
     * <p>The second half is the Reassembly. A twin stamped in the attic stays there while the train
     * carries its pair on past the band's end, and {@link ClientUpsideDownBand#isInBand} stops at that
     * end, so a player in such a twin got the world's cut and saw the whole world under the room. The
     * structure box is the server's own answer to "is this camera in a twin", so it cannot mistake a
     * Reassembly mountaintop over the lid height for an attic the way a bare height test would.</p>
     */
    private static boolean atticCanBeHere(int camX, double cameraX, double cameraY, double cameraZ) {
        if (ClientUpsideDownBand.isInBand(camX)) return true;
        return DungeonTrainCommonConfig.isUpsideDownBedrockRoof()
            && ClientPortalRoomDepth.isInsideStructure(cameraX, cameraY, cameraZ);
    }

    private static boolean sameTrim(PortalSealPlane.Cut a, PortalSealPlane.Cut b) {
        return a.trimMinY() == b.trimMinY() && a.trimMaxY() == b.trimMaxY();
    }

    /**
     * Re-mesh the section layers whose trimmed rows just changed, so the blocks the trim skips go (or
     * come back) on the next compile rather than whenever those sections next happen to change.
     *
     * <p>A trim only exists for a camera inside sealed space, so this runs on the frame a portal swap
     * crosses a seal — the arrival the swap already treats as a loading moment — and never while
     * riding: one layer of sections across the view. Both renderers rebuild through
     * {@link LevelRenderer#setSectionDirty}; Sodium redirects it to its own scheduler.</p>
     */
    private static void rebuildTrimmedSections(PortalSealPlane.Cut before, PortalSealPlane.Cut after,
                                               double cameraX, double cameraZ) {
        LevelRenderer renderer = Minecraft.getInstance().levelRenderer;
        if (renderer == null || Minecraft.getInstance().level == null) return;
        int radius = Minecraft.getInstance().options.getEffectiveRenderDistance() + 1;
        int centreX = SectionPos.blockToSectionCoord(Mth.floor(cameraX));
        int centreZ = SectionPos.blockToSectionCoord(Mth.floor(cameraZ));
        markLayers(renderer, before, centreX, centreZ, radius);
        markLayers(renderer, after, centreX, centreZ, radius);
    }

    private static void markLayers(LevelRenderer renderer, PortalSealPlane.Cut cut,
                                   int centreX, int centreZ, int radius) {
        if (cut.trimMinY() > cut.trimMaxY()) return;
        int fromY = SectionPos.blockToSectionCoord(cut.trimMinY());
        int toY = SectionPos.blockToSectionCoord(cut.trimMaxY());
        for (int sy = fromY; sy <= toY; sy++) {
            for (int sx = centreX - radius; sx <= centreX + radius; sx++) {
                for (int sz = centreZ - radius; sz <= centreZ + radius; sz++) {
                    renderer.setSectionDirty(sx, sy, sz);
                }
            }
        }
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

    /** How far along X this client can see — the window in which the sunk band's valleys can show. */
    private static int viewReach() {
        return (Minecraft.getInstance().options.getEffectiveRenderDistance() + 1) * 16;
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

    /**
     * Whether the chunk mesher should skip the block (or fluid) at {@code (x, y, z)} — a row of sealed
     * world inside a section the cut keeps. Called for every block meshed, from build threads.
     *
     * <p>Bounded to the camera's surroundings as well as by row, because the mesher also builds Sable
     * sub-levels: a carriage's plot keeps world Y, so a row test alone would strip a train passing
     * through those heights. Their plots are far beyond any render distance.</p>
     */
    public static boolean hidesBlock(int x, int y, int z) {
        if (!cut.hidesBlock(y)) return false;
        int reach = trimReach;
        return Math.abs(x - trimCentreX) <= reach && Math.abs(z - trimCentreZ) <= reach;
    }

    /** Whether a box spanning {@code [minY, maxY]} is wholly on the hidden side of this frame's cut. */
    public static boolean hides(double minY, double maxY) {
        return cut.hides(minY, maxY);
    }
}
