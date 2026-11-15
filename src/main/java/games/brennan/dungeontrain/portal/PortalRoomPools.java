package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.editor.ChiseledBookshelfSync;
import games.brennan.dungeontrain.editor.ContainerContentsPool;
import games.brennan.dungeontrain.editor.ContainerContentsRoller;
import games.brennan.dungeontrain.editor.ContainerContentsStore;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Rolls the authored loot of the containers a dimensional carriage's room <b>template</b> carries —
 * the chests, barrels and pots the author built into the room itself rather than into a variant cell.
 *
 * <p>Until this pass existed nothing rolled them. {@code PortalCarriageBuilder.applyRoomVariants}
 * walks variant entries only, so a template chest kept whatever items the editor plot baked into the
 * room's {@code .nbt} when it was saved: the same loot in every copy, every pair on the train, and on
 * every Test the Carriage reseed. This is the room's twin of
 * {@code CarriageContentsPlacer.applyContentPools}, the authored-pool half.</p>
 *
 * <p><b>Same two frames as the variant pass.</b> The seed frame is {@code variantIndex} — the
 * pair-and-copy identity, salted by a test's reseed, so a reseed rolls the chest afresh while a live
 * copy re-stamped by the tiling window reproduces the chest the player left. The difficulty frame is
 * {@code pairKey}, the entry corridor's real carriage index (see
 * {@code PortalCarriageBuilder.applyRoomVariants} for why the two are never the same number).</p>
 */
final class PortalRoomPools {

    private PortalRoomPools() {}

    /**
     * Roll every authored pool in {@code roomName}'s room that no variant cell owns, into the block
     * entity the room stamp just placed there. A cell whose block holds no container is skipped by the
     * roller, so a pool left over from an earlier build of the room is harmless.
     */
    static void apply(ServerLevel level, BlockPos roomOrigin, String roomName, Vec3i size,
                      PortalCorridorMask mask, int variantIndex, int pairKey) {
        ContainerContentsStore store =
            ContainerContentsStore.loadFor(ContainerContentsStore.trackPlotKey(TrackKind.PORTAL_ROOM, roomName));
        Set<BlockPos> poolCells = store.allPositions();
        if (poolCells.isEmpty()) return;
        Set<BlockPos> variantCells = new HashSet<>();
        for (var entry : TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, roomName, size).entries()) {
            variantCells.add(entry.localPos());
        }
        long worldSeed = level.getSeed();
        for (BlockPos local : cellsToRoll(poolCells, variantCells, size,
                local -> mask.covers(roomOrigin.offset(local)))) {
            ContainerContentsPool pool = store.poolAt(local);
            if (pool.isEmpty()) continue;
            rollInto(level, roomOrigin.offset(local), local, pool, worldSeed, variantIndex, pairKey);
        }
    }

    /**
     * The pool cells this pass rolls: inside the room, not owned by a variant cell (that pass rolls
     * those, and the author's variant pick wins), and not under the corridor mask (the twins' cells
     * are never re-stamped). Pure, for the test.
     */
    static List<BlockPos> cellsToRoll(Set<BlockPos> poolCells, Set<BlockPos> variantCells, Vec3i size,
                                      Predicate<BlockPos> masked) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos local : poolCells) {
            if (variantCells.contains(local)) continue;
            if (!inside(local, size)) continue;
            if (masked.test(local)) continue;
            out.add(local);
        }
        return out;
    }

    private static boolean inside(BlockPos local, Vec3i size) {
        return local.getX() >= 0 && local.getY() >= 0 && local.getZ() >= 0
            && local.getX() < size.getX() && local.getY() < size.getY() && local.getZ() < size.getZ();
    }

    private static void rollInto(ServerLevel level, BlockPos world, BlockPos local, ContainerContentsPool pool,
                                 long worldSeed, int variantIndex, int pairKey) {
        BlockState state = level.getBlockState(world);
        if (!state.hasBlockEntity()) return;
        if (!ContainerContentsRoller.isLootAuthorable(state) && !ContainerContentsRoller.isDecoratedPot(state)) return;
        BlockEntity be = level.getBlockEntity(world);
        if (be == null) return;
        CompoundTag baseNbt = be.saveWithFullMetadata(level.registryAccess());
        CompoundTag rolled = ContainerContentsRoller.roll(pool, state, local, worldSeed, variantIndex,
            /*diffIndex*/ pairKey, baseNbt, level.registryAccess(), level);
        if (rolled == null) return;
        be.loadCustomOnly(rolled, level.registryAccess());
        be.setChanged();
        ChiseledBookshelfSync.syncIfNeeded(level, world);
    }
}
