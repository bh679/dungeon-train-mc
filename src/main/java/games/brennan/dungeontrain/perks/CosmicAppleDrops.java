package games.brennan.dungeontrain.perks;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.template.TestGate;
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
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A cosmic apple turns up in about one carriage group in {@link #EVERY} (plus {@link TimedPotApple}'s pot
 * apple every ten minutes on the train): one stack of one, in one chest,
 * barrel or shulker box of that group. Every Diff-Level, Level 0 included, gets at least {@link #MIN_PER_LEVEL}:
 * a level whose groups win fewer draws tops up with groups picked by seed ({@link #isFloorGroup}); each side
 * of the origin counts on its own, as Diff-Level uses distance. Which apple depends on depth
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
    static final int EVERY = 10;
    /** Every Diff-Level (each side of the origin) gets at least this many apple groups. */
    static final int MIN_PER_LEVEL = 1;
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
        if (groupSize <= 0) return -1;
        // A test stamp's Diff-Level override pins every group to one level: no real levels to floor.
        boolean floor = TestGate.levelOr(-1) < 0;
        return slotFor(seed, anchorPIdx, groupSize, DifficultyProgression.positionTier(anchorPIdx), floor,
                DungeonTrainConfig.getCarriagesPerTier(), DungeonTrainConfig.getProgressionLevelDelay(),
                DungeonTrainConfig.getDifficultyTravelledOffset());
    }

    /** Pure {@link #slotFor(long, int, int)}: {@code level} is the anchor's Diff-Level, the rest the live config. */
    static int slotFor(long seed, int anchorPIdx, int groupSize, int level, boolean floor,
                       int carriagesPerTier, int delay, int offset) {
        if (groupSize <= 0 || level < 0) return -1;
        long groupIndex = Math.floorDiv(anchorPIdx, groupSize);
        if (groupGetsApple(seed, groupIndex)) return (int) Math.floorMod(hash(seed + 1, groupIndex), groupSize);
        // A level's guaranteed apples start at the first carriage, so the hand-on past a carriage with no
        // free container (TrainAssembler) can try every carriage of the group.
        if (floor && isFloorGroup(seed, groupIndex, anchorPIdx < 0, level, carriagesPerTier, delay, offset, groupSize)) return 0;
        return -1;
    }

    /**
     * True when {@code groupIndex} carries one of its level's guaranteed apples. A level (one side of the
     * origin) whose groups win fewer than {@link #MIN_PER_LEVEL} draws ({@link #groupGetsApple}) tops up from
     * the groups that missed: they are ranked by seed and the lowest ones take the missing apples.
     */
    static boolean isFloorGroup(long seed, long groupIndex, boolean negativeSide, int level,
                                int carriagesPerTier, int delay, int offset, int groupSize) {
        long[] range = levelGroups(level, negativeSide, carriagesPerTier, delay, offset, groupSize);
        if (range == null || groupIndex < range[0] || groupIndex > range[1]) return false;
        if (groupGetsApple(seed, groupIndex)) return false;
        long count = range[1] - range[0] + 1;
        long won = 0;
        long ahead = 0;
        long mine = floorRank(seed, groupIndex);
        for (long g = range[0]; g <= range[1]; g++) {
            if (groupGetsApple(seed, g)) {
                won++;
            } else if (g != groupIndex) {
                long rank = floorRank(seed, g);
                if (rank < mine || (rank == mine && g < groupIndex)) ahead++;
            }
        }
        long missing = Math.min(MIN_PER_LEVEL, count) - won;
        return ahead < missing;
    }

    /** Seeded order in which a level's missed groups take its guaranteed apples (lowest first). */
    private static long floorRank(long seed, long groupIndex) {
        return hash(seed + 4, groupIndex);
    }

    /**
     * The first and last group index whose anchor ({@code index * groupSize}) sits at Diff-Level
     * {@code level} (0 or more) on one side of the origin, or null when no anchor does. Solved from
     * {@code DifficultyProgression.effectiveTier(max(0, abs(anchor) + offset), carriagesPerTier, delay)}:
     * the level holds shifted distances {@code [(level + delay) * cpt, (level + delay + 1) * cpt - 1]},
     * and Level 0 everything from the origin up to {@code (delay + 1) * cpt - 1}.
     * The negative side starts at anchor -1 (anchor 0 belongs to the positive side).
     */
    static long[] levelGroups(int level, boolean negativeSide, int carriagesPerTier, int delay, int offset,
                              int groupSize) {
        if (level < 0 || groupSize <= 0) return null;
        long cpt = Math.max(1, carriagesPerTier);
        long band = (long) level + Math.max(0, delay);
        long lo = level == 0 ? Long.MIN_VALUE / 4 : band * cpt - offset;
        long hi = (band + 1) * cpt - 1 - offset;
        long minAbs = negativeSide ? 1 : 0;
        lo = Math.max(lo, minAbs);
        if (hi < lo) return null;
        long first;
        long last;
        if (negativeSide) {
            first = Math.floorDiv(-hi + groupSize - 1, (long) groupSize);
            last = Math.floorDiv(-lo, (long) groupSize);
        } else {
            first = Math.floorDiv(lo + groupSize - 1, (long) groupSize);
            last = Math.floorDiv(hi, (long) groupSize);
        }
        return first > last ? null : new long[] {first, last};
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

    /** One apple of the tier {@link #tierFor} draws for {@code index} at {@code diffLevel}. */
    static ItemStack rollApple(long seed, long index, int diffLevel) {
        return new ItemStack(appleFor(tierFor(seed, index, diffLevel)));
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
     *
     * @return true when an apple went in
     */
    public static boolean place(ServerLevel level, BoundingBox box, long seed, int anchorPIdx) {
        List<Container> containers = containersIn(level, box);
        if (containers.isEmpty()) {
            LOGGER.debug("[DT-CosmicApple] group at {} drew an apple but has no container", anchorPIdx);
            return false;
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
                return true;
            }
        }
        return false;
    }

    /**
     * The group's chests had no room for its apple by the last carriage: put it in a decorated pot in one of
     * {@code boxes} instead (in place of whatever loot it held), picked from the seed like {@link #place}'s
     * container, an empty pot first. Breaking the pot drops it.
     *
     * @return true when an apple went in
     */
    public static boolean placeInPot(ServerLevel level, List<BoundingBox> boxes, long seed, int anchorPIdx) {
        List<DecoratedPotBlockEntity> empty = new ArrayList<>();
        List<DecoratedPotBlockEntity> filled = new ArrayList<>();
        for (BoundingBox box : boxes) {
            for (DecoratedPotBlockEntity pot : potsIn(level, box)) (pot.getTheItem().isEmpty() ? empty : filled).add(pot);
        }
        List<DecoratedPotBlockEntity> pots = empty.isEmpty() ? filled : empty;
        if (pots.isEmpty()) {
            LOGGER.debug("[DT-CosmicApple] group at {} drew an apple but has no container or pot", anchorPIdx);
            return false;
        }
        Item apple = appleFor(tierFor(seed, anchorPIdx, DifficultyProgression.positionTier(anchorPIdx)));
        DecoratedPotBlockEntity pot = pots.get(potIndex(seed, anchorPIdx, pots.size()));
        pot.setTheItem(new ItemStack(apple));
        pot.setChanged();
        LOGGER.info("[DT-CosmicApple] carriage {} -> {} in a pot at {}", anchorPIdx, apple, pot.getBlockPos());
        return true;
    }

    /** Which of {@code count} pots takes the apple: seeded, so a group's pick never changes. */
    static int potIndex(long seed, int anchorPIdx, int count) {
        return (int) Math.floorMod(hash(seed + 4, anchorPIdx), (long) count);
    }

    /** The decorated pots inside {@code box}, in block order, empty or holding loot. */
    static List<DecoratedPotBlockEntity> potsIn(ServerLevel level, BoundingBox box) {
        List<DecoratedPotBlockEntity> found = new ArrayList<>();
        for (BlockEntity be : blockEntitiesIn(level, box)) {
            if (be instanceof DecoratedPotBlockEntity pot) found.add(pot);
        }
        return found;
    }

    private static List<BlockEntity> blockEntitiesIn(ServerLevel level, BoundingBox box) {
        List<BlockEntity> found = new ArrayList<>();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                for (BlockEntity be : level.getChunk(cx, cz).getBlockEntities().values()) {
                    if (box.isInside(be.getBlockPos())) found.add(be);
                }
            }
        }
        found.sort(Comparator.comparingLong(be -> be.getBlockPos().asLong()));
        return found;
    }

    static List<Container> containersIn(ServerLevel level, BoundingBox box) {
        List<Container> out = new ArrayList<>();
        for (BlockEntity be : blockEntitiesIn(level, box)) {
            if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity
                    || be instanceof ShulkerBoxBlockEntity) out.add((Container) be);
        }
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
