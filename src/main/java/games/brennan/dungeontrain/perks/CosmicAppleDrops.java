package games.brennan.dungeontrain.perks;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
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
 * A cosmic apple turns up in about one carriage group in {@link #EVERY}: one stack of one, in one chest,
 * barrel or shulker box of that group, never in the first band. Which apple depends on depth
 * ({@link #tierFor}): only Cosmic Apples until Diff-Level {@link #BITE_FROM}, Celestial Bites from there,
 * Violet Cores from {@link #CORE_FROM}. Deterministic per world seed and group, so a regenerated group
 * rolls the same way the rest of its loot does. Called from {@code TrainAssembler.spawnGroup} right after
 * a carriage's contents pass, before a fresh shared carriage registers (so the apple never counts as an
 * author's edit).
 */
public final class CosmicAppleDrops {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One group in this many gets an apple. */
    static final int EVERY = 5;
    /** First Diff-Level that can hold a Celestial Bite. */
    static final int BITE_FROM = 5;
    /** First Diff-Level that can hold a Violet Core. */
    static final int CORE_FROM = 10;
    /** Chance in 100 of a Celestial Bite from {@link #BITE_FROM}, before {@link #CORE_FROM}. */
    static final int BITE_SHALLOW = 20;
    /** Chances in 100 from {@link #CORE_FROM}: Celestial Bite, then Violet Core. */
    static final int BITE_DEEP = 25;
    static final int CORE_DEEP = 10;
    /** Salt so this draw is independent of the portal lottery and the loot rolls. */
    private static final long SALT = 0xC05_41C_DEB_215L;

    private CosmicAppleDrops() {}

    /** Which slot of the group holds the apple, or -1 when this group gets none. */
    public static int slotFor(long seed, int anchorPIdx, int groupSize) {
        if (groupSize <= 0 || DifficultyProgression.positionTier(anchorPIdx) == 0) return -1;
        long groupIndex = Math.floorDiv(anchorPIdx, groupSize);
        if (!groupGetsApple(seed, groupIndex)) return -1;
        return (int) Math.floorMod(hash(seed + 1, groupIndex), groupSize);
    }

    /** The pure draw: true for about one group in {@link #EVERY}. */
    static boolean groupGetsApple(long seed, long groupIndex) {
        return Math.floorMod(hash(seed, groupIndex), EVERY) == 0;
    }

    /** Apple tier for a drop at {@code diffLevel}: 1 Cosmic Apple, 2 Celestial Bite, 3 Violet Core. */
    static int tierFor(long seed, long index, int diffLevel) {
        if (diffLevel < BITE_FROM) return 1;
        int roll = (int) Math.floorMod(hash(seed + 3, index), 100L);
        if (diffLevel < CORE_FROM) return roll < BITE_SHALLOW ? 2 : 1;
        if (roll < CORE_DEEP) return 3;
        return roll < CORE_DEEP + BITE_DEEP ? 2 : 1;
    }

    private static Item appleFor(int tier) {
        return switch (tier) {
            case 3 -> ModItems.VIOLET_CORE.get();
            case 2 -> ModItems.CELESTIAL_BITE.get();
            default -> ModItems.COSMIC_APPLE.get();
        };
    }

    /**
     * Put one apple in one container inside {@code box} (a carriage's shipyard box, already filled),
     * its tier drawn from the depth of {@code anchorPIdx}.
     * Nothing happens when the box holds no chest, barrel or shulker box with a free slot.
     */
    public static void place(ServerLevel level, BoundingBox box, long seed, int anchorPIdx) {
        List<Container> containers = containersIn(level, box);
        if (containers.isEmpty()) {
            LOGGER.debug("[DT-CosmicApple] group at {} drew an apple but has no container", anchorPIdx);
            return;
        }
        Item apple = appleFor(tierFor(seed, anchorPIdx, DifficultyProgression.positionTier(anchorPIdx)));
        int start = (int) Math.floorMod(hash(seed + 2, anchorPIdx), containers.size());
        for (int i = 0; i < containers.size(); i++) {
            Container c = containers.get((start + i) % containers.size());
            for (int s = 0; s < c.getContainerSize(); s++) {
                if (!c.getItem(s).isEmpty()) continue;
                c.setItem(s, new ItemStack(apple));
                c.setChanged();
                LOGGER.info("[DT-CosmicApple] carriage {} -> {} at {}", anchorPIdx, apple, ((BlockEntity) c).getBlockPos());
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
