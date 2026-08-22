package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

/**
 * The floor and ceiling planes an {@link PortalRoomMode#ENDLESS_OPEN} tile gets under
 * {@link PortalRoomCopies.Kind#SINGLE} — one block, repeated, instead of the authored room's own
 * two planes.
 *
 * <h2>Written here rather than stamped</h2>
 * <p>Nothing is stamped at all for such a tile: {@link PortalRoomTiler#writeMaskFor} masks the whole
 * box out of the write, so the template pass, the contents pass and the variant pass each find every
 * cell covered and place nothing. That is what keeps this cheap and, more importantly, safe — no
 * chest is ever placed and filled, so none of the loot-spray hazards those passes are documented
 * against (see {@code PortalRoomTiler.stampTile} and {@code PortalCarriageBuilder.applyRoomVariants})
 * have anything to fire on. The clear mask is untouched, so the tile is still emptied of the rock it
 * landed in before these two planes go down.</p>
 *
 * <h2>Masked by the CLEAR mask, not the write mask</h2>
 * <p>The write mask covers everything by construction here, so honouring it would write nothing.
 * What these planes must dodge is the corridors — the twins and any extra way back to the train
 * standing in this tile — and that is exactly what the clear mask holds. A plane written over a
 * corridor would fill its floor or its roof with somebody else's block and, on the door plane, take
 * the doorway with it.</p>
 */
public final class PortalRoomSinglePlanes {

    private PortalRoomSinglePlanes() {}

    /**
     * The block {@code blockId} names, or empty when nothing in the registry answers to it.
     *
     * <p>Empty rather than {@link Blocks#AIR} for a name that does not resolve. Air here would be a
     * tile with no floor — a hole in the plain that drops a player out of the world — so a caller
     * that cannot get a block must fall back to stamping the room normally rather than write what it
     * was asked for. A block id reaches us from a hand-editable tag and from a mod that may not be
     * installed on the next launch, so this is a live case and not a defensive one.</p>
     */
    public static Optional<BlockState> stateFor(String blockId) {
        if (blockId == null || blockId.isBlank()) return Optional.empty();
        ResourceLocation id = ResourceLocation.tryParse(blockId.trim());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return Optional.empty();
        Block block = BuiltInRegistries.BLOCK.get(id);
        if (block == null || block == Blocks.AIR) return Optional.empty();
        return Optional.of(block.defaultBlockState());
    }

    /**
     * Fill the tile's floor plane and ceiling plane at {@code origin} with {@code state}.
     *
     * <p>The full cross-section of both, edge columns included: Endless Open has no side walls, so
     * there is no wall row for the planes to stop short of — every cell of each plane is plain floor
     * or plain roof. Only the two planes are touched; everything between them was cleared to air by
     * the stamp and is what makes the space open.</p>
     *
     * @param clearMask the corridors standing in this tile, whose cells are left alone
     * @param relight   as the stamp was called with — a lit write costs the light engine a pass, an
     *                  unlit one is section-local and is what the bulk paths use
     */
    public static void write(ServerLevel level, BlockPos origin, Vec3i size, BlockState state,
                             PortalCorridorMask clearMask, boolean relight) {
        int x0 = origin.getX();
        int x1 = x0 + size.getX() - 1;
        int z0 = origin.getZ();
        int z1 = z0 + size.getZ() - 1;
        int floorY = origin.getY();
        int ceilingY = floorY + size.getY() - 1;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                setPlaneBlock(level, pos.set(x, floorY, z), state, clearMask, relight);
                // A room one block tall would have its floor and its ceiling in the same plane, and
                // the second write would be the first one again. PortalRoomLayout.MIN_HEIGHT rules
                // that out, but the guard costs a comparison and the alternative is a silent
                // double-write if the floor ever moves.
                if (ceilingY != floorY) {
                    setPlaneBlock(level, pos.set(x, ceilingY, z), state, clearMask, relight);
                }
            }
        }
    }

    /** One plane cell, skipped where a corridor owns it. Mirrors {@code PortalCarriageBuilder.setRoomBlock}. */
    private static void setPlaneBlock(ServerLevel level, BlockPos pos, BlockState state,
                                      PortalCorridorMask clearMask, boolean relight) {
        if (clearMask.covers(pos)) return;
        if (relight) {
            level.setBlock(pos, state, Block.UPDATE_ALL);
        } else {
            SilentBlockOps.setBlockSectionLocal(level, pos, state);
        }
    }
}
