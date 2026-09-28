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

    /**
     * Open rows a floor needs over it to take a doorway — a player's height and one more, so a
     * mouth under a low overhang is passed over for the cavern floor below it.
     */
    private static final int DOORWAY_HEADROOM = 3;

    /**
     * The row of the ground the sample was anchored on: the air row the room is cut around is
     * {@link PortalChunkTerrain#SURFACE_ROW}, and this is the block under it.
     */
    private static final int GROUND_ROW = PortalChunkTerrain.SURFACE_ROW - 1;

    /** {@link #surfaceOf}'s answer for a column that is rock with no floor in it anywhere. */
    static final int BURIED = -1;

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
        for (int x = Math.max(0, xFrom); x <= xTo && x < slice.width(); x++) {
            for (int z = zFrom; z <= zTo && z < slice.width(); z++) {
                int row = surfaceOf(slice, x, z, max);
                // A buried column says nothing about where the ground is; the others decide.
                if (row != BURIED) rows[count++] = row;
            }
        }
        // Every column buried — a mouth cut straight into rock. The door goes on the row the room is
        // anchored on, and the doorway is dug through to the open cave from there
        // (PortalChunkDimension#openDoorway).
        if (count == 0) return Math.max(0, Math.min(max, GROUND_ROW));
        int[] found = Arrays.copyOf(rows, count);
        Arrays.sort(found);
        return Math.max(0, Math.min(max, found[count / 2]));
    }

    /**
     * One column's surface: the floor a doorway there should stand on, capped at {@code max}.
     *
     * <p><b>The floor nearest the room's own ground, not the highest solid block.</b> Above an
     * overworld hillside there is only sky, so the highest solid cell and the ground are the same
     * block. In a Nether cavern the highest solid cell is the <i>ceiling</i>: read top-down, every
     * mouth was stood up in the rock over the cave, the apron cleared its two columns of it, and a
     * player arrived facing a wall of netherrack. So a floor is a solid cell with
     * {@link #DOORWAY_HEADROOM} open rows over it, and of those, the one closest to the row the
     * sample was anchored on — the cavern floor the room is actually cut around.</p>
     *
     * <p>A column whose only floor is above the cap is ground rising past what the box can spend, and
     * clamps to {@code max}. One with no floor anywhere but rock under the cap is {@link #BURIED} and
     * left out of the median. One with nothing solid at all is open sky down to the template's
     * floor, {@code 0}.</p>
     *
     * <p><b>The block, not the air above it.</b> A door-height offset places the corridor's own
     * <i>floor row</i> — {@code PortalTestCommand} puts an arriving player at {@code origin + 1} —
     * so answering the row a player's feet occupy stands the whole doorway one block high, which
     * then has to be dug out of the hillside behind it to be walkable. Reading the ground block
     * itself lays the corridor floor flush with the terrain, and the apron has almost nothing left
     * to clear.</p>
     */
    static int surfaceOf(PortalChunkSlice slice, int x, int z, int max) {
        int best = -1;
        boolean aboveCap = false;
        for (int y = slice.height() - 1; y > 0; y--) {
            if (!isGround(slice.at(x, y, z)) || !openAbove(slice, x, y, z)) continue;
            if (y > max) {
                aboveCap = true;
                continue;
            }
            if (best < 0 || Math.abs(y - GROUND_ROW) < Math.abs(best - GROUND_ROW)) best = y;
        }
        if (best >= 0) return best;
        // Ground rising past what the box can spend: the doorway stands as high as it can.
        if (aboveCap) return max;
        for (int y = Math.min(max, slice.height() - 1); y > 0; y--) {
            if (isGround(slice.at(x, y, z))) return BURIED;
        }
        // A column with nothing solid under the cap at all — open sky down to the room's floor. The
        // floor is the template's own, so the doorway stands on that.
        return 0;
    }

    /**
     * What a player can stand on, not merely what is not air. Now that the sample is decorated, "not
     * air" is grass, a flower, a snow layer or a sapling as readily as it is the ground under them —
     * and standing the doorway on a tuft of grass puts its floor one block above the dirt beside it,
     * which is a step down out of every door it happens to.
     */
    private static boolean isGround(BlockState state) {
        return state != null && state.blocksMotion();
    }

    /**
     * Whether the {@link #DOORWAY_HEADROOM} rows over {@code y} are open. Past the top of the cut is
     * not known to be open — over a Nether floor it is as likely the roof — so it does not count.
     */
    private static boolean openAbove(PortalChunkSlice slice, int x, int y, int z) {
        for (int h = 1; h <= DOORWAY_HEADROOM; h++) {
            if (y + h >= slice.height() || isGround(slice.at(x, y + h, z))) return false;
        }
        return true;
    }
}
