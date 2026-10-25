package games.brennan.dungeontrain.perks;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Cosmic Debris turns up in about one carriage group in {@link #EVERY}: one stack of one, in one chest,
 * barrel or shulker box of that group, never in the first band. Deterministic per world seed and group,
 * so a regenerated group rolls the same way the rest of its loot does. Called from
 * {@code TrainAssembler.spawnGroup} right after a carriage's contents pass, before a fresh shared
 * carriage registers (so the debris never counts as an author's edit).
 */
public final class CosmicDebrisDrops {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One group in this many gets a debris. */
    static final int EVERY = 5;
    /** Salt so this draw is independent of the portal lottery and the loot rolls. */
    private static final long SALT = 0xC05_41C_DEB_215L;

    private CosmicDebrisDrops() {}

    /** Which slot of the group holds the debris, or -1 when this group gets none. */
    public static int slotFor(long seed, int anchorPIdx, int groupSize) {
        if (groupSize <= 0 || DifficultyProgression.positionTier(anchorPIdx) == 0) return -1;
        long groupIndex = Math.floorDiv(anchorPIdx, groupSize);
        if (!groupGetsDebris(seed, groupIndex)) return -1;
        return (int) Math.floorMod(hash(seed + 1, groupIndex), groupSize);
    }

    /** The pure draw: true for about one group in {@link #EVERY}. */
    static boolean groupGetsDebris(long seed, long groupIndex) {
        return Math.floorMod(hash(seed, groupIndex), EVERY) == 0;
    }

    /**
     * Put one debris in one container inside {@code box} (a carriage's shipyard box, already filled).
     * Nothing happens when the box holds no chest, barrel or shulker box with a free slot.
     */
    public static void place(ServerLevel level, BoundingBox box, long seed, int anchorPIdx) {
        List<Container> containers = containersIn(level, box);
        if (containers.isEmpty()) {
            LOGGER.debug("[DT-Debris] group at {} drew debris but has no container", anchorPIdx);
            return;
        }
        int start = (int) Math.floorMod(hash(seed + 2, anchorPIdx), containers.size());
        for (int i = 0; i < containers.size(); i++) {
            Container c = containers.get((start + i) % containers.size());
            for (int s = 0; s < c.getContainerSize(); s++) {
                if (!c.getItem(s).isEmpty()) continue;
                c.setItem(s, new ItemStack(ModItems.COSMIC_DEBRIS.get()));
                c.setChanged();
                LOGGER.info("[DT-Debris] carriage {} -> {}", anchorPIdx, ((BlockEntity) c).getBlockPos());
                return;
            }
        }
    }

    private static List<Container> containersIn(ServerLevel level, BoundingBox box) {
        List<BlockEntity> found = new ArrayList<>();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                for (BlockEntity be : level.getChunk(cx, cz).getBlockEntities().values()) {
                    if (!box.isInside(be.getBlockPos())) continue;
                    if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity
                            || be instanceof ShulkerBoxBlockEntity) found.add(be);
                }
            }
        }
        found.sort(Comparator.comparingLong(be -> be.getBlockPos().asLong()));
        List<Container> out = new ArrayList<>(found.size());
        for (BlockEntity be : found) out.add((Container) be);
        return out;
    }

    /** SplitMix-style mix (same shape as {@code PortalCarriageSelection.hash}); non-negative. */
    static long hash(long seed, long index) {
        long h = (seed ^ SALT) ^ (index * 0x9E3779B97F4A7C15L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return (h ^ (h >>> 31)) >>> 1;
    }
}
