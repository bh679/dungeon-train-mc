package games.brennan.dungeontrain.portal.chunkframe;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.portal.PortalCorridorMask;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
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
        FORCED.clear();
    }

    /** The frame a pair's room is dressed in: its template and its variant sidecar. */
    public record Picked(String name, ChunkFrameTemplate template, TrackVariantBlocks variants) {}

    /**
     * Frames forced onto particular pairs — what Test the Carriage uses to show one frame on a
     * chunk dimension it picked, whatever the frames' own selection says. Pair-keyed, cleared with
     * the world.
     */
    private static final java.util.Map<Integer, String> FORCED = new ConcurrentHashMap<>();

    /** Dress pair {@code pairKey} in {@code frameName} (or its normal pick when null). */
    public static void force(int pairKey, String frameName) {
        if (frameName == null) FORCED.remove(pairKey); else FORCED.put(pairKey, frameName);
    }

    /**
     * {@code roomName}'s frame for pair {@code pairKey}, or empty when no frame dresses it.
     *
     * <p>The candidates are every frame whose {@link ChunkFrameMeta} names this room (or every room),
     * picked by their weights on a per-pair roll, so a room keeps its frame every time it is
     * re-stamped.</p>
     */
    public static Optional<Picked> frameFor(ServerLevel level, String roomName, int pairKey) {
        String name = FORCED.get(pairKey);
        if (name == null) name = pick(roomName, level.getSeed() ^ FRAME_SALT ^ ((long) pairKey << 20));
        if (name == null) return Optional.empty();
        Optional<ChunkFrameTemplate> template = ChunkFrameStore.get(level, name);
        if (template.isEmpty()) {
            if (WARNED.add(roomName + "/" + name)) {
                LOGGER.warn("[DungeonTrain] Chunk room {} picked frame '{}', which does not load; it stays unframed",
                    roomName, name);
            }
            return Optional.empty();
        }
        return Optional.of(new Picked(name, template.get(), ChunkFrameVariants.loadFor(name)));
    }

    /** The frame {@code seed} lands on among those that dress {@code roomName}, or null. */
    static String pick(String roomName, long seed) {
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.List<Integer> weights = new java.util.ArrayList<>();
        int total = 0;
        for (String name : ChunkFrameRegistry.names()) {
            ChunkFrameMeta meta = ChunkFrameMetaStore.get(name);
            if (!meta.appliesTo(roomName)) continue;
            names.add(name);
            weights.add(meta.weight());
            total += meta.weight();
        }
        if (total == 0) return null;
        long mixed = seed * 0x9E3779B97F4A7C15L;
        int roll = (int) Math.floorMod(mixed ^ (mixed >>> 31), (long) total);
        for (int i = 0; i < names.size(); i++) {
            roll -= weights.get(i);
            if (roll < 0) return names.get(i);
        }
        return names.get(names.size() - 1);
    }

    /**
     * Write {@code picked} around and into the room at {@code roomOrigin}.
     *
     * <p>A cell with block variants rolls one — per pair, so a room keeps its roll every time it is
     * re-stamped — and the roll stands in for the template's own block there. An empty roll is air,
     * which here as anywhere in a frame means "leave what is there".</p>
     *
     * @param mask the pair's corridors and plugs, never written
     * @return cells changed
     */
    public static int place(ServerLevel level, Picked picked, BlockPos roomOrigin, PortalCorridorMask mask, int pairKey) {
        ChunkFrameTemplate frame = picked.template();
        TrackVariantBlocks variants = picked.variants();
        long seed = level.getSeed();
        int changed = 0;
        Vec3i size = frame.size();
        BlockPos.MutableBlockPos local = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    BlockState state = frame.at(x, y, z);
                    CompoundTag blockEntity = frame.blockEntityAt(x, y, z);
                    if (variants.statesAt(local.set(x, y, z)) != null) {
                        VariantState roll = variants.resolve(local.immutable(), seed, pairKey);
                        if (roll == null || roll.isMob()) continue;
                        state = CarriageVariantBlocks.isEmptyPlaceholder(roll.state()) ? null : roll.state();
                        blockEntity = roll.blockEntityNbt();
                    }
                    if (state == null || state.isAir()) continue;
                    // Stage placeholders become the stage's real blocks, as in every other stamp.
                    state = games.brennan.dungeontrain.train.StagePlacementScope.resolve(state);
                    cursor.set(roomOrigin.getX() + ChunkFrame.OFFSET.getX() + x,
                        roomOrigin.getY() + ChunkFrame.OFFSET.getY() + y,
                        roomOrigin.getZ() + ChunkFrame.OFFSET.getZ() + z);
                    if (mask.covers(cursor)) continue;
                    BlockState current = level.getBlockState(cursor);
                    if (current == state) continue;
                    if (current.hasBlockEntity()) level.removeBlockEntity(cursor);
                    level.setBlock(cursor, state, Block.UPDATE_ALL);
                    applyBlockEntity(level, cursor, blockEntity);
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
