package games.brennan.dungeontrain.provenance;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

import java.util.List;
import java.util.OptionalInt;

/**
 * Gear saved into a template's storage, marked new once the template is stamped. A stamp copies it
 * in as saved, unmarked, so the first player to take it would otherwise be told it is older than the
 * train remembers ({@link ItemProvenance}).
 */
public final class BakedGear {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BakedGear() {}

    /**
     * Mark the gear in every container inside the {@code size} box at {@code origin} as new, with
     * carriage {@code pIdx} when given.
     *
     * <p>Run it before anything fingerprints the box's contents as placed
     * ({@code DifficultyWorldLootEvents.fingerprintRoom}): the fingerprint hashes the items' data,
     * so marking afterwards would make every baked chest look touched.</p>
     */
    public static void markStorage(ServerLevel level, BlockPos origin, Vec3i size, OptionalInt pIdx) {
        BlockPos max = origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1);
        int marked = 0;
        for (int cx = origin.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = origin.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                // Positions, not the live map: a block entity the stamp just wrote may still be pending,
                // and getBlockEntity promotes it.
                for (BlockPos pos : List.copyOf(chunk.getBlockEntitiesPos())) {
                    if (pos.getX() < origin.getX() || pos.getY() < origin.getY() || pos.getZ() < origin.getZ()
                            || pos.getX() > max.getX() || pos.getY() > max.getY() || pos.getZ() > max.getZ()) continue;
                    if (level.getBlockEntity(pos) instanceof Container container
                            && container instanceof BlockEntity be
                            && ItemProvenance.markFreshContents(container, pIdx)) {
                        be.setChanged();
                        marked++;
                    }
                }
            }
        }
        if (marked > 0) {
            LOGGER.debug("[DungeonTrain] Provenance: marked baked gear new in {} container(s) at {} pIdx={}",
                marked, origin, pIdx);
        }
    }
}
