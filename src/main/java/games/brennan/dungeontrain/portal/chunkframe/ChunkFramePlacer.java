package games.brennan.dungeontrain.portal.chunkframe;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.PortalCorridorMask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dressing a dimensional carriage room in its frame ({@link ChunkFrame}).
 *
 * <p>One rule for both regions: <b>a frame's blocks are written, its air is not</b>. In the shell
 * that leaves the lock skin (the skybox) wherever the author left the frame open, so no frame can
 * open a way out into the basement; inside the room it leaves the sampled terrain, so a frame can be
 * a colonnade with a hillside between the columns, or fill the room as deep as the author likes.</p>
 *
 * <p>The pair's corridors and plugs are skipped ({@code corridorMask} without the seal planes), so
 * the frame is stamped whole and each corridor keeps its own blocks where it passes through — the
 * doorway is cut wherever the corridor lands. The doorway's two cells are re-opened afterwards by
 * {@code PortalChunkDimension.openDoorway}, as for an unframed room.</p>
 */
public final class ChunkFramePlacer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Salt so a room's frame roll is not any other roll taken on the same pair. */
    private static final long FRAME_SALT = 0x5DEECE66DL;

    /** Rooms already warned about, so an unloadable frame is said once, not on every write. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private ChunkFramePlacer() {}

    /** Forget the per-session warnings — a new world may have the missing frames. */
    public static void clear() {
        WARNED.clear();
    }

    /** {@code roomName}'s frame for pair {@code pairKey}, or empty when it has none that loads. */
    public static Optional<ChunkFrameTemplate> frameFor(ServerLevel level, String roomName, int pairKey) {
        String name = ChunkRoomFramesStore.get(roomName).pick(level.getSeed() ^ FRAME_SALT ^ ((long) pairKey << 20));
        if (name == null) return Optional.empty();
        Optional<ChunkFrameTemplate> template = ChunkFrameStore.get(level, name);
        if (template.isEmpty() && WARNED.add(roomName + "/" + name)) {
            LOGGER.warn("[DungeonTrain] Chunk room {} names frame '{}', which does not load; it stays unframed",
                roomName, name);
        }
        return template;
    }

    /**
     * Write {@code frame} around and into the room at {@code roomOrigin}.
     *
     * @param mask the pair's corridors and plugs, never written
     * @return cells changed
     */
    public static int place(ServerLevel level, ChunkFrameTemplate frame, BlockPos roomOrigin, PortalCorridorMask mask) {
        int changed = 0;
        Vec3i size = frame.size();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    BlockState state = frame.at(x, y, z);
                    if (state.isAir()) continue;
                    cursor.set(roomOrigin.getX() + ChunkFrame.OFFSET.getX() + x,
                        roomOrigin.getY() + ChunkFrame.OFFSET.getY() + y,
                        roomOrigin.getZ() + ChunkFrame.OFFSET.getZ() + z);
                    if (mask.covers(cursor)) continue;
                    BlockState current = level.getBlockState(cursor);
                    if (current == state) continue;
                    if (current.hasBlockEntity()) level.removeBlockEntity(cursor);
                    level.setBlock(cursor, state, Block.UPDATE_ALL);
                    applyBlockEntity(level, cursor, frame.blockEntityAt(x, y, z));
                    changed++;
                }
            }
        }
        return changed;
    }

    private static void applyBlockEntity(ServerLevel level, BlockPos pos, CompoundTag nbt) {
        if (nbt == null) return;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) return;
        blockEntity.loadWithComponents(nbt, level.registryAccess());
        blockEntity.setChanged();
    }
}
