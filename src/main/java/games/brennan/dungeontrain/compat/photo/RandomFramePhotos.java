package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.block.entity.PhotographFrameBlockEntity;
import games.brennan.dungeontrain.portal.PortalCorridorMask;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.ContentsFlip;
import games.brennan.dungeontrain.train.ContentsFlip.Flip;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A {@code dungeontrain:random_playerphoto} hung in a photo frame in a template becomes a community
 * photo each time the template is stamped on the train — the frame's counterpart of the chest roll in
 * {@code ContainerContentsRoller}, drawn from the same {@link SharedPhotos#rollFound} pool.
 *
 * <p>{@link #resolveIn} runs after a contents stamp, beside the player-head pass. Editor plots are
 * stamped with {@link CarriageContentsPlacer#EDITOR_SENTINEL_PIDX} and keep the placeholder, so an
 * author still sees which frames will take a photo.</p>
 *
 * <p>The pool is empty for the first moments of a server and whenever the relay is off. A frame
 * stamped then is left empty and marked pending, and {@link #tickPending} fills it once the pool has
 * a photo — the flag only ever comes from a stamp, so editor frames never resolve on their own.</p>
 */
public final class RandomFramePhotos {

    /** Salt keeping this roll independent of every other deterministic roll on the same cell. */
    private static final long SALT_FRAME_PHOTO = 0xF4A3E9407051DEL;

    private static final long MIX_X = 0x9E3779B97F4A7C15L;
    private static final long MIX_Y = 0xBF58476D1CE4E5B9L;
    private static final long MIX_Z = 0x94D049BB133111EBL;
    private static final long MIX_C = 0xCAFEBABE12345678L;

    /** How often a pending frame asks the pool again. */
    private static final int PENDING_RETRY_TICKS = 40;

    private RandomFramePhotos() {}

    /** Swap every random-photo placeholder hung in the stamped box for a community photo. */
    public static void resolveIn(ServerLevel level, BlockPos origin, Vec3i size, long seed, int carriageIndex,
                                 PortalCorridorMask mask, Flip flip) {
        if (carriageIndex == CarriageContentsPlacer.EDITOR_SENTINEL_PIDX) return;
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) return;
        for (int x = 0; x < size.getX(); x++) {
            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < size.getZ(); z++) {
                    BlockPos localPos = new BlockPos(x, y, z);
                    BlockPos worldPos = origin.offset(localPos);
                    if (mask.covers(worldPos)) continue;
                    if (!(level.getBlockEntity(worldPos) instanceof PhotographFrameBlockEntity frame)) continue;
                    if (!frame.hasRandomPlaceholder()) continue;
                    // mapLocal is its own inverse: the authored cell, so the pick follows the frame.
                    BlockPos authored = ContentsFlip.mapLocal(localPos, size, flip);
                    ItemStack found = SharedPhotos.rollFound(seedFor(seed, carriageIndex, authored));
                    if (found.isEmpty()) {
                        frame.markPendingRandomPhoto();
                    } else {
                        frame.setPhoto(found);
                    }
                }
            }
        }
    }

    /** Server ticker for every frame master; only a pending frame does any work. */
    public static void tickPending(Level level, BlockPos pos, BlockState state, PhotographFrameBlockEntity frame) {
        if (!frame.pendingRandomPhoto() || frame.hasPhoto()) return;
        if (Math.floorMod(level.getGameTime() + pos.hashCode(), PENDING_RETRY_TICKS) != 0) return;
        ItemStack found = SharedPhotos.rollFound(seedFor(level.getGameTime(), 0, pos));
        if (!found.isEmpty()) frame.setPhoto(found);
    }

    /** Deterministic per-cell seed for the photo a frame draws. */
    static long seedFor(long seed, int carriageIndex, BlockPos cell) {
        long state = seed
            ^ ((long) cell.getX() * MIX_X)
            ^ ((long) cell.getY() * MIX_Y)
            ^ ((long) cell.getZ() * MIX_Z)
            ^ ((long) carriageIndex * MIX_C)
            ^ SALT_FRAME_PHOTO;
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        return state ^ (state >>> 31);
    }
}
