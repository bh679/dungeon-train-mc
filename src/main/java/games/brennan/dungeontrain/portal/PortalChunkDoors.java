package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.BlockState;

import org.slf4j.Logger;

import java.util.Arrays;

/**
 * Putting a {@link PortalRoomMode#CHUNK_DIMENSION} room's two doorways on the ground the sample
 * actually landed.
 *
 * <h2>The doors move, not the terrain</h2>
 * <p>A slice is cut with the sampled chunk's <i>centre</i> column on
 * {@link PortalChunkTerrain#SURFACE_ROW}, which says nothing about the rows at the two ends: real
 * terrain slopes, so a doorway fixed at that row opens into a hillside at one end and over a drop at
 * the other. A room has always been able to stand each of its doorways at its own height
 * ({@link PortalRoomSettings#doorHeightOffset}, {@link PortalRoomSettings#exitDoorHeightOffset}) —
 * an author's control, spent here on the terrain instead, so the doorway meets the ground rather
 * than the ground being flattened to meet the doorway.</p>
 *
 * <h2>Why this has to happen before the pair is planned</h2>
 * <p>The offsets place the room's box and both corridor lanes, so they must be settled before a
 * single block is written — which is why {@code PortalCarriageBuilder.planStructure} waits for the
 * sample rather than stamping and adjusting afterwards. A door that moved after a player could see
 * it would move the frame they were standing in.</p>
 */
public final class PortalChunkDoors {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * How many columns in from a mouth the ground is measured — the same two columns the doorway's
     * apron keeps clear, so what is measured is what a player steps onto.
     */
    private static final int MOUTH_DEPTH = 2;

    private PortalChunkDoors() {}

    /**
     * {@code settings} with both doorways stood on the ground {@code slice} put under them.
     *
     * <p>Each end is measured on its own: the two offsets are independent, and a chunk that slopes
     * along the walk is exactly the case they exist for.</p>
     */
    public static PortalRoomSettings fit(PortalRoomSettings settings, PortalChunkSlice slice,
                                         CarriageDims dims, PortalCarriageLayout layout,
                                         Vec3i size) {
        if (slice == null) return settings;
        int[] zSpan = doorwayColumns(dims, layout, size, settings.doorOffset().value());
        int max = PortalRoomLayout.maxDoorHeightOffset(dims, size.getY());

        int entry = groundRow(slice, 0, MOUTH_DEPTH - 1, zSpan[0], zSpan[1], max);
        int exit = groundRow(slice, size.getX() - MOUTH_DEPTH, size.getX() - 1, zSpan[0], zSpan[1], max);

        LOGGER.info("[DungeonTrain] Chunk dimension doors: entry row {}, exit row {} (of {} allowed)",
            entry, exit, max);
        return settings
            .withDoorHeightOffset(new PortalRoomDoorHeightOffset(entry))
            .withExitDoorHeightOffset(new PortalRoomDoorHeightOffset(exit));
    }

    /**
     * The room-local Z span a corridor's mouth covers, as {@code {min, max}}.
     *
     * <p>Read off {@link PortalRoomLayout#roomOrigin} with the corridor at the origin rather than
     * re-derived: the same arithmetic that will place the real room places this one, so the columns
     * measured here are the columns a player walks through. Held inside the room's own interior,
     * since the ±Z walls are the template's and carry no ground.</p>
     */
    private static int[] doorwayColumns(CarriageDims dims, PortalCarriageLayout layout, Vec3i size,
                                        int doorOffset) {
        BlockPos room = PortalRoomLayout.roomOrigin(BlockPos.ZERO, dims, layout, size.getZ(), doorOffset);
        int min = Math.max(1, -room.getZ());
        int max = Math.min(size.getZ() - 2, -room.getZ() + dims.width() - 1);
        return new int[] {min, Math.max(min, max)};
    }

    /**
     * The row a doorway should stand on for the columns {@code [xFrom..xTo] × [zFrom..zTo]} — the
     * median of those columns' surfaces, held inside {@code 0..max}.
     *
     * <p>The median rather than the mean or the highest: one pillar of stone in front of a mouth
     * should not lift the doorway three blocks, and one crevice should not drop it into the floor.
     * What a player reads as "the ground here" is what most of the columns are doing.</p>
     */
    private static int groundRow(PortalChunkSlice slice, int xFrom, int xTo, int zFrom, int zTo,
                                 int max) {
        int count = 0;
        int[] rows = new int[Math.max(1, (xTo - xFrom + 1) * (zTo - zFrom + 1))];
        for (int x = Math.max(0, xFrom); x <= xTo && x < slice.size(); x++) {
            for (int z = zFrom; z <= zTo && z < slice.size(); z++) {
                rows[count++] = surfaceOf(slice, x, z, max);
            }
        }
        if (count == 0) return Math.min(PortalChunkTerrain.SURFACE_ROW, max);
        int[] found = Arrays.copyOf(rows, count);
        Arrays.sort(found);
        return Math.max(0, Math.min(max, found[count / 2]));
    }

    /**
     * One column's surface: its highest cell a player would stand on, capped at {@code max}.
     *
     * <p><b>The block, not the air above it.</b> A door-height offset places the corridor's own
     * <i>floor row</i> — {@code PortalTestCommand} puts an arriving player at {@code origin + 1} —
     * so answering the row a player's feet occupy stands the whole doorway one block high, which
     * then has to be dug out of the hillside behind it to be walkable. Reading the ground block
     * itself lays the corridor floor flush with the terrain, and the apron has almost nothing left
     * to clear.</p>
     */
    private static int surfaceOf(PortalChunkSlice slice, int x, int z, int max) {
        for (int y = Math.min(max, slice.size() - 1); y > 0; y--) {
            BlockState here = slice.at(x, y, z);
            // What a player can stand on, not merely what is not air. Now that the sample is
            // decorated, "not air" is grass, a flower, a snow layer or a sapling as readily as it is
            // the ground under them — and standing the doorway on a tuft of grass puts its floor one
            // block above the dirt beside it, which is a step down out of every door it happens to.
            if (here != null && here.blocksMotion()) return y;
        }
        // A column with nothing solid under the cap at all — open sky down to the room's floor. The
        // floor is the template's own, so the doorway stands on that.
        return 0;
    }
}
