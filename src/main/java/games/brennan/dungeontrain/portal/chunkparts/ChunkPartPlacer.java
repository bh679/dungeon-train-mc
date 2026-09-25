package games.brennan.dungeontrain.portal.chunkparts;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.PortalCorridorMask;
import games.brennan.dungeontrain.train.CarriagePartAssignment;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Framing a dimensional carriage in its chunk parts.
 *
 * <h2>The two layers are written by different rules</h2>
 * <ul>
 *   <li><b>Outer</b> (outside the room box, where the lock skin is): every cell is the part's. Where
 *       the author left air, the <b>lock</b> is written instead — a frame can decorate the seal but
 *       never open it, so no part drawn with a hole in it leaves a way out into the basement.</li>
 *   <li><b>Inner</b> (the box's own edge row, where the sampled terrain runs): a solid part cell
 *       overwrites the terrain, an air cell leaves the terrain as it is. That is what lets a frame be a
 *       colonnade with a hillside showing between the columns.</li>
 * </ul>
 *
 * <p>Both skip the pair's two corridors and their plugs ({@code corridorMask} without the seal
 * planes): the door part is stamped whole and the corridor keeps its own blocks where it passes
 * through it, which is the automatic cut. The doorway's own two cells are re-opened afterwards by
 * {@code PortalChunkDimension.openDoorway}, as for an unframed room.</p>
 *
 * <h2>All four, or none</h2>
 * <p>A frame is the four kinds together. If any pick is {@code none}, or names a part that does not
 * load, the whole frame is dropped for that pair and the room stands in its skin — a frame with a
 * side missing is a room with a wall of sky where a wall should be.</p>
 */
public final class ChunkPartPlacer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Salt so a room's frame roll is not the same roll a carriage's parts take at the same index. */
    private static final long FRAME_SALT = 0x5DEECE66DL;

    /** Rooms already warned about, so an unloadable frame is said once, not on every write. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private ChunkPartPlacer() {}

    /** The parts one pair's room is framed in, per kind, one per placement. */
    public record Frame(Map<ChunkPartKind, List<ChunkPart>> parts) {}

    /** Forget the per-session warnings — a new world may have the missing parts. */
    public static void clear() {
        WARNED.clear();
    }

    /**
     * {@code roomName}'s frame for pair {@code pairKey}, or null when the room has none or it cannot
     * be completed.
     */
    public static Frame frameFor(ServerLevel level, String roomName, int pairKey) {
        Optional<CarriagePartAssignment> assignment = ChunkRoomPartsStore.get(roomName);
        if (assignment.isEmpty()) return null;
        long seed = level.getSeed() ^ FRAME_SALT;
        Map<ChunkPartKind, List<ChunkPart>> parts = new EnumMap<>(ChunkPartKind.class);
        for (ChunkPartKind kind : ChunkPartKind.values()) {
            List<String> names = assignment.get().pickPerPlacement(kind.carriageKind(), seed, pairKey);
            List<ChunkPart> loaded = new ArrayList<>(names.size());
            for (String name : names) {
                Optional<ChunkPart> part = CarriagePartKind.NONE.equals(name)
                    ? Optional.empty() : ChunkPartStore.get(level, kind, name);
                if (part.isEmpty()) {
                    if (WARNED.add(roomName)) {
                        LOGGER.warn("[DungeonTrain] Chunk room {} has no loadable {} part '{}'; it stays "
                            + "unframed (a frame needs all four kinds)", roomName, kind.id(), name);
                    }
                    return null;
                }
                loaded.add(part.get());
            }
            if (loaded.size() != kind.placements().size()) return null;
            parts.put(kind, loaded);
        }
        return new Frame(parts);
    }

    /**
     * Write {@code frame} around the room at {@code roomOrigin}.
     *
     * @param mask the pair's corridors and plugs, never written
     * @param lock what an outer-layer air cell becomes
     * @return cells changed
     */
    public static int place(ServerLevel level, Frame frame, BlockPos roomOrigin, PortalCorridorMask mask,
                            BlockState lock) {
        int changed = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (ChunkPartKind kind : ChunkPartKind.values()) {
            List<ChunkPart> parts = frame.parts().get(kind);
            List<ChunkPartKind.Placement> placements = kind.placements();
            for (int i = 0; i < placements.size(); i++) {
                changed += placeOne(level, kind, placements.get(i), parts.get(i), roomOrigin, mask, lock, cursor);
            }
        }
        return changed;
    }

    private static int placeOne(ServerLevel level, ChunkPartKind kind, ChunkPartKind.Placement placement,
                                ChunkPart part, BlockPos roomOrigin, PortalCorridorMask mask,
                                BlockState lock, BlockPos.MutableBlockPos cursor) {
        int changed = 0;
        var size = part.size();
        for (int ly = 0; ly < size.getY(); ly++) {
            for (int lz = 0; lz < size.getZ(); lz++) {
                for (int lx = 0; lx < size.getX(); lx++) {
                    BlockPos local = kind.roomLocal(placement, lx, ly, lz);
                    BlockState state = cellState(part.at(lx, ly, lz).mirror(placement.mirror()),
                        ChunkPartKind.isOuter(local.getX(), local.getY(), local.getZ()), lock);
                    if (state == null) continue;
                    cursor.set(roomOrigin.getX() + local.getX(), roomOrigin.getY() + local.getY(),
                        roomOrigin.getZ() + local.getZ());
                    if (mask.covers(cursor)) continue;
                    if (level.getBlockState(cursor) == state) continue;
                    if (level.getBlockState(cursor).hasBlockEntity()) level.removeBlockEntity(cursor);
                    level.setBlock(cursor, state, Block.UPDATE_ALL);
                    applyBlockEntity(level, cursor, part.blockEntityAt(lx, ly, lz));
                    changed++;
                }
            }
        }
        return changed;
    }

    /**
     * What one frame cell writes: the part's block, the lock where an outer cell is air, and nothing
     * (null) where an inner cell is air — the terrain there stays.
     */
    static BlockState cellState(BlockState partState, boolean outer, BlockState lock) {
        if (!partState.isAir()) return partState;
        return outer ? lock : null;
    }

    private static void applyBlockEntity(ServerLevel level, BlockPos pos, CompoundTag nbt) {
        if (nbt == null) return;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) return;
        blockEntity.loadWithComponents(nbt, level.registryAccess());
        blockEntity.setChanged();
    }
}
