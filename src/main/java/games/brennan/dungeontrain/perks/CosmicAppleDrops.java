package games.brennan.dungeontrain.perks;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * barrel or shulker box of that group, never at Diff-Level 0 (the first 40 carriages). Which apple depends on depth
 * ({@link #tierFor}): the chance of a Celestial Bite and of a Violet Core each follow a curve over Diff-Level
 * ({@link AppleTierCurve#BITE}, {@link AppleTierCurve#CORE}), rising gently to 25% and 10%; the rest are
 * Cosmic Apples. Deterministic per world seed and group, so a regenerated group
 * rolls the same way the rest of its loot does. Called from {@code TrainAssembler.spawnGroup} right after
 * a carriage's contents pass, before a fresh shared carriage registers (so the apple never counts as an
 * author's edit). Nothing drops until someone has unlocked a perk ({@link #open}).
 */
public final class CosmicAppleDrops {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One group in this many gets an apple. */
    static final int EVERY = 5;
    /** The tier roll is out of this many: basis points, so the curves keep two decimals of a per cent. */
    static final int ROLL_RANGE = 10_000;
    /** Salt so this draw is independent of the portal lottery and the loot rolls. */
    private static final long SALT = 0xC05_41C_DEB_215L;

    private CosmicAppleDrops() {}

    /**
     * Apples only turn up once someone online has spent this many unlock points: before the first
     * perk is unlocked there is nothing to spend an apple's points on. Judged when a group
     * generates; unlocks are never taken back, so once open it stays open.
     */
    static final int UNLOCK_SPENT_TO_OPEN = 1;

    /** True once any online player has spent {@link #UNLOCK_SPENT_TO_OPEN} unlock points. */
    public static boolean open(MinecraftServer server) {
        if (server == null || !PerksGate.isOn()) return false;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (opensAt(PerkSpend.unlockSpent(player))) return true;
        }
        return false;
    }

    static boolean opensAt(int unlockSpent) {
        return unlockSpent >= UNLOCK_SPENT_TO_OPEN;
    }

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
        int core = coreChance(diffLevel);
        int bite = biteChance(diffLevel);
        if (core + bite <= 0) return 1;
        int roll = (int) Math.floorMod(hash(seed + 3, index), (long) ROLL_RANGE);
        if (roll < core) return 3;
        return roll < core + bite ? 2 : 1;
    }

    /** Chance of a Celestial Bite at {@code diffLevel}, in basis points of {@link #ROLL_RANGE}. */
    static int biteChance(int diffLevel) {
        return AppleTierCurve.BITE.basisPointsAt(diffLevel);
    }

    /** Chance of a Violet Core at {@code diffLevel}, in basis points of {@link #ROLL_RANGE}. */
    static int coreChance(int diffLevel) {
        return AppleTierCurve.CORE.basisPointsAt(diffLevel);
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
