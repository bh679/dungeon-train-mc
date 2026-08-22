package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import games.brennan.dungeontrain.editor.RotationApplier;
import games.brennan.dungeontrain.editor.VariantState;
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
     * Fill the tile's floor plane and ceiling plane at {@code origin} from {@code palette}.
     *
     * <p>The full cross-section of both, edge columns included: Endless Open has no side walls, so
     * there is no wall row for the planes to stop short of — every cell of each plane is plain floor
     * or plain roof. Only the two planes are touched; everything between them was cleared to air by
     * the stamp and is what makes the space open.</p>
     *
     * <p><b>Rolled per cell, on the cell's position within the tile.</b> That is what makes a
     * multi-block palette read as a mix rather than as a checkerboard, and it costs nothing to make
     * the two Copies values mean here what they mean everywhere else: the roll also takes
     * {@code variantIndex}, which is one value for every tile under Exact and a per-tile value under
     * Dynamic. So an Exact plain is one mix repeated and a Dynamic plain is a fresh mix each tile,
     * both of them stable under a player walking back over ground they have already crossed.</p>
     *
     * @param clearMask    the corridors standing in this tile, whose cells are left alone
     * @param relight      as the stamp was called with — a lit write costs the light engine a pass,
     *                     an unlit one is section-local and is what the bulk paths use
     * @param variantIndex the copy's identity, {@code PortalStructure.variantIndexFor}
     */
    public static void write(ServerLevel level, BlockPos origin, Vec3i size,
                             PortalRoomCopiesPalette palette, PortalCorridorMask clearMask,
                             boolean relight, long worldSeed, int variantIndex) {
        if (palette == null || palette.isEmpty()) return;
        int x0 = origin.getX();
        int x1 = x0 + size.getX() - 1;
        int z0 = origin.getZ();
        int z1 = z0 + size.getZ() - 1;
        int floorY = origin.getY();
        int ceilingY = floorY + size.getY() - 1;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                setPlaneBlock(level, pos.set(x, floorY, z), origin, palette, clearMask, relight,
                    worldSeed, variantIndex);
                // A room one block tall would have its floor and its ceiling in the same plane, and
                // the second write would be the first one again. PortalRoomLayout.MIN_HEIGHT rules
                // that out, but the guard costs a comparison and the alternative is a silent
                // double-write if the floor ever moves.
                if (ceilingY != floorY) {
                    setPlaneBlock(level, pos.set(x, ceilingY, z), origin, palette, clearMask,
                        relight, worldSeed, variantIndex);
                }
            }
        }
    }

    /**
     * One plane cell, skipped where a corridor owns it. Mirrors {@code PortalCarriageBuilder.setRoomBlock}.
     *
     * <p>The picker is handed the cell's position <b>relative to the tile</b>, not its world
     * position. Two tiles rolling the same variant index must lay the same mix — that is what Exact
     * means — and a world position would make every tile's mix differ regardless of the setting.</p>
     */
    private static void setPlaneBlock(ServerLevel level, BlockPos pos, BlockPos origin,
                                      PortalRoomCopiesPalette palette, PortalCorridorMask clearMask,
                                      boolean relight, long worldSeed, int variantIndex) {
        if (clearMask.covers(pos)) return;
        BlockPos local = pos.subtract(origin);
        VariantState picked = palette.resolve(local, worldSeed, variantIndex);
        if (picked == null) return;

        // The whole entry, not just its block. A variant carries a facing and a slab half as well as
        // a state, and dropping them would place every log in a palette on the axis it happened to be
        // copied from — the visible half of "it uses the variant you gave it". Lock id 0: a palette is
        // one list with no group to agree with, so each cell rolls its own facing under RANDOM.
        BlockState state = RotationApplier.apply(picked.state(), picked.rotation(), picked.half(),
            local, worldSeed, variantIndex, /*lockId*/ 0);

        // A block entity in a floor plane is legal — an author can put a chest in a palette — and it
        // has to be evicted rather than written over, or its contents spray across the floor. Same
        // hazard, same fix, as PortalCarriageBuilder.applyRoomVariants documents at length.
        if (state.hasBlockEntity() || level.getBlockState(pos).hasBlockEntity()) {
            SilentBlockOps.evictBlockEntity(level.getChunkAt(pos), pos);
        }
        if (picked.hasBlockEntityData()) {
            // Carries the authored NBT — a sign's text, a banner's pattern. setBlockSilent is the
            // only writer that stamps it onto the fresh block entity.
            SilentBlockOps.setBlockSilent(level, pos, state, picked.blockEntityNbt());
        } else if (relight) {
            level.setBlock(pos, state, Block.UPDATE_ALL);
        } else {
            SilentBlockOps.setBlockSectionLocal(level, pos, state);
        }
    }
}
