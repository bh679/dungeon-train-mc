package games.brennan.dungeontrain.combat;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import games.brennan.dungeontrain.difficulty.DifficultyApplier;
import games.brennan.dungeontrain.difficulty.ToolTiers;
import games.brennan.dungeontrain.ship.CarriageDeck;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import games.brennan.dungeontrain.train.MobBreakContext;
import games.brennan.dungeontrain.train.PlayerBlockBreakOff;
import games.brennan.dungeontrain.train.PlayerPlacedTrainBlocks;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import org.joml.Vector3d;
import org.joml.primitives.AABBdc;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * A tool-carrying mob ({@link DifficultyApplier#DIG_TIER_KEY}) digging through to a target it has
 * seen. Runs when the mob has seen its target recently, has stopped closing the distance, and the
 * cells in front of it (head height, then feet, plus overhead when the target is above) are all
 * blocks it may break: {@link ToolTiers#mayBreak} at its break tier, never below its feet.
 *
 * <p>Breaks one block at a time, taking as long as a player would with the same tool (tool speed,
 * Efficiency, Haste / Mining Fatigue, water and airborne penalties, right-tool 1/30 vs 1/100). A
 * carriage block is read and broken in its Sable plot ({@link CarriageDeck}); it drops at its world
 * cell through {@link PlayerBlockBreakOff#breakOff}, inside {@link MobBreakContext} so a shared
 * carriage does not upload the hole on its own.</p>
 */
public final class MobDigGoal extends Goal {

    static final int PRIORITY = 1;
    /** How long after last seeing its target a mob still digs toward it. */
    private static final int SEEN_MEMORY_TICKS = 200;
    /** No closer to the target for this long counts as stuck. */
    private static final int STUCK_TICKS = 40;
    /** Getting this much closer resets the stuck clock. */
    private static final double PROGRESS_EPS = 0.5;
    /** Within this the mob's own attack has it; no digging. */
    private static final double MELEE_RANGE = 2.0;
    private static final double MAX_TARGET_RANGE = 24.0;
    /** Max distance from the mob's eyes to the block centre while digging. */
    private static final double REACH = 3.0;
    /** Blocks that would take longer than this are not attempted. */
    private static final int MAX_DIG_TICKS = 30 * 20;
    /** After a break, the next cell may start straight away for this long. */
    private static final int CHAIN_TICKS = 20;
    private static final int SWING_EVERY_TICKS = 5;
    private static final double CRACK_RANGE_SQR = 32.0 * 32.0;
    /** Padding around a carriage's box when looking for the carriage a cell belongs to. */
    private static final double CARRIAGE_PAD = 2.0;

    /**
     * One cell to break. A carriage cell keeps its ship and plot position (the world cell moves with
     * the train); a world cell has {@code ship == null}.
     */
    private record DigCell(BlockPos worldPos, ManagedShip ship, ServerSubLevel subLevel, BlockPos plotPos,
                           BlockState state, InteractionHand hand, int ticks) {
        boolean onCarriage() { return ship != null; }
        BlockPos levelPos() { return onCarriage() ? plotPos : worldPos; }
        Vec3 worldCentre() {
            if (!onCarriage()) return Vec3.atCenterOf(worldPos);
            Vector3d c = ship.shipToWorld(new Vector3d(plotPos.getX() + 0.5, plotPos.getY() + 0.5, plotPos.getZ() + 0.5));
            return new Vec3(c.x, c.y, c.z);
        }
    }

    private final Mob mob;
    private final int tier;

    private int lastSeenTick = -SEEN_MEMORY_TICKS * 2;
    private LivingEntity trackedTarget;
    private double bestDistance = Double.MAX_VALUE;
    private int bestDistanceTick;
    private int chainUntilTick = Integer.MIN_VALUE;

    private DigCell cell;
    private int progressTicks;
    private int lastStage;

    private MobDigGoal(Mob mob, int tier) {
        this.mob = mob;
        this.tier = tier;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /**
     * Give {@code mob} its dig goal if it carries a break tier and doesn't have one yet. Safe to call
     * from both the spawn roll and a load from disk.
     */
    public static void attach(Mob mob) {
        if (mob.level().isClientSide()) return;
        int tier = mob.getPersistentData().getInt(DifficultyApplier.DIG_TIER_KEY);
        if (tier <= ToolTiers.NO_TIER) return;
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof MobDigGoal) return;
        }
        mob.goalSelector.addGoal(PRIORITY, new MobDigGoal(mob, tier));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public boolean canUse() {
        if (!(mob.level() instanceof ServerLevel level)) return false;
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) {
            trackedTarget = null;
            return false;
        }
        int now = mob.tickCount;
        if (target != trackedTarget) {
            trackedTarget = target;
            bestDistance = Double.MAX_VALUE;
            bestDistanceTick = now;
        }
        if (mob.getSensing().hasLineOfSight(target)) lastSeenTick = now;
        if (now - lastSeenTick > SEEN_MEMORY_TICKS) return false;

        double distance = mob.distanceTo(target);
        if (distance > MAX_TARGET_RANGE || distance <= MELEE_RANGE) return false;
        if (distance < bestDistance - PROGRESS_EPS) {
            bestDistance = distance;
            bestDistanceTick = now;
        }
        if (now >= chainUntilTick && now - bestDistanceTick < STUCK_TICKS) return false;
        if (!EventHooks.canEntityGrief(level, mob)) return false;

        cell = planDig(level, target);
        if (cell == null) {
            // Nothing breakable in the way: wait out another stuck window before looking again.
            bestDistanceTick = now;
            chainUntilTick = Integer.MIN_VALUE;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (cell == null || !(mob.level() instanceof ServerLevel level)) return false;
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) return false;
        if (!currentState(level, cell).equals(cell.state)) return false;
        if (!(mob.getItemInHand(cell.hand).getItem() instanceof DiggerItem)) return false;
        return mob.getEyePosition().distanceTo(cell.worldCentre()) <= REACH;
    }

    @Override
    public void start() {
        mob.getNavigation().stop();
        progressTicks = 0;
        lastStage = -1;
    }

    @Override
    public void tick() {
        if (cell == null || !(mob.level() instanceof ServerLevel level)) return;
        Vec3 centre = cell.worldCentre();
        mob.getLookControl().setLookAt(centre.x, centre.y, centre.z);
        if (progressTicks % SWING_EVERY_TICKS == 0) mob.swing(cell.hand);
        progressTicks++;
        int stage = Math.min(9, progressTicks * 10 / Math.max(1, cell.ticks));
        if (stage != lastStage) {
            sendCrack(level, cell, stage);
            lastStage = stage;
        }
        if (progressTicks >= cell.ticks) {
            finishBreak(level, cell);
        }
    }

    @Override
    public void stop() {
        if (cell != null && lastStage >= 0 && mob.level() instanceof ServerLevel level) {
            sendCrack(level, cell, -1);
        }
        cell = null;
        lastStage = -1;
    }

    private void finishBreak(ServerLevel level, DigCell target) {
        sendCrack(level, target, -1);
        lastStage = -1;
        boolean broke;
        if (target.onCarriage()) {
            BlockPos worldNow = BlockPos.containing(target.worldCentre());
            broke = MobBreakContext.run(() -> PlayerBlockBreakOff.breakOff(
                    level, target.ship, target.subLevel, target.plotPos, worldNow));
        } else {
            broke = level.destroyBlock(target.worldPos, canHarvest(mob.getItemInHand(target.hand), target.state), mob);
        }
        if (broke) {
            EquipmentSlot slot = target.hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
            mob.getItemInHand(target.hand).hurtAndBreak(1, mob, slot);
            chainUntilTick = mob.tickCount + CHAIN_TICKS;
        }
        cell = null;
    }

    /**
     * The first blocking cell between the mob and {@code target}, or null when there is none or any
     * blocking cell is one it may not break (it could not get through anyway).
     */
    private DigCell planDig(ServerLevel level, LivingEntity target) {
        BlockPos feet = mob.blockPosition();
        double dx = target.getX() - mob.getX();
        double dz = target.getZ() - mob.getZ();
        boolean targetAbove = target.getY() - mob.getY() > 1.5;
        BlockPos front = feet.relative(Direction.getNearest(dx, 0, dz));

        List<BlockPos> candidates = new ArrayList<>(3);
        if (targetAbove) candidates.add(feet.above(2));
        candidates.add(front.above());
        candidates.add(front);

        List<ManagedShip> ships = shipsNear(level);
        DigCell first = null;
        for (BlockPos pos : candidates) {
            DigCell c = resolve(level, ships, pos, feet.getY());
            if (c == null) continue;           // open: nothing to break here
            if (c.ticks < 0) return null;      // blocking and not ours to break
            if (first == null) first = c;
        }
        return first;
    }

    /**
     * The cell at world {@code pos}: null when open, a cell with {@code ticks < 0} when it blocks but
     * may not be broken, otherwise the cell with its hand and dig time.
     */
    private DigCell resolve(ServerLevel level, List<ManagedShip> ships, BlockPos pos, int feetY) {
        ManagedShip ship = null;
        ServerSubLevel subLevel = null;
        BlockPos plotPos = null;
        BlockState state = null;
        for (ManagedShip s : ships) {
            BlockState onShip = CarriageDeck.blockAt(s, pos);
            if (!onShip.isAir()) {
                ship = s;
                subLevel = ((SableManagedShip) s).subLevel();
                plotPos = CarriageDeck.shipLocal(s, pos);
                state = onShip;
                break;
            }
        }
        if (state == null) {
            if (!level.isLoaded(pos)) return null;
            state = level.getBlockState(pos);
        }
        if (state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) return null;

        DigCell blocked = new DigCell(pos, ship, subLevel, plotPos, state, InteractionHand.MAIN_HAND, -1);
        float hardness = state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (hardness < 0 || state.hasBlockEntity()) return blocked;

        InteractionHand hand = bestHand(state);
        if (hand == null) return blocked;
        ItemStack tool = mob.getItemInHand(hand);
        boolean playerPlaced = subLevel != null && PlayerPlacedTrainBlocks.isPlayerPlaced(subLevel, plotPos);
        boolean floor = pos.getY() < feetY;
        if (!ToolTiers.mayBreak(tier, playerPlaced, tool.isCorrectToolForDrops(state), floor)) return blocked;

        BlockPos levelPos = plotPos != null ? plotPos : pos;
        if (!EventHooks.onEntityDestroyBlock(mob, levelPos, state)) return blocked;

        int ticks = digTicks(level, tool, state, hardness);
        if (ticks > MAX_DIG_TICKS) return blocked;
        return new DigCell(pos, ship, subLevel, plotPos, state, hand, ticks);
    }

    /** The held digging tool that breaks {@code state} best: a right tool first, then the faster. */
    private InteractionHand bestHand(BlockState state) {
        InteractionHand best = null;
        float bestScore = -1;
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = mob.getItemInHand(hand);
            if (!(stack.getItem() instanceof DiggerItem)) continue;
            float score = stack.getDestroySpeed(state) + (stack.isCorrectToolForDrops(state) ? 1000 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = hand;
            }
        }
        return best;
    }

    /** Ticks a player holding {@code tool} under the mob's conditions would take to break {@code state}. */
    private int digTicks(ServerLevel level, ItemStack tool, BlockState state, float hardness) {
        if (hardness == 0) return 1;
        float speed = tool.getDestroySpeed(state);
        if (speed > 1.0F) {
            int efficiency = EnchantmentHelper.getItemEnchantmentLevel(
                    level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY), tool);
            if (efficiency > 0) speed += efficiency * efficiency + 1;
        }
        if (MobEffectUtil.hasDigSpeed(mob)) {
            speed *= 1.0F + (MobEffectUtil.getDigSpeedAmplification(mob) + 1) * 0.2F;
        }
        var fatigue = mob.getEffect(MobEffects.DIG_SLOWDOWN);
        if (fatigue != null) {
            speed *= switch (fatigue.getAmplifier()) {
                case 0 -> 0.3F;
                case 1 -> 0.09F;
                case 2 -> 0.0027F;
                default -> 8.1E-4F;
            };
        }
        if (mob.isEyeInFluid(FluidTags.WATER)) speed /= 5.0F;
        if (!mob.onGround()) speed /= 5.0F;
        float perTick = speed / hardness / (canHarvest(tool, state) ? 30.0F : 100.0F);
        if (perTick <= 0) return Integer.MAX_VALUE;
        return (int) Math.ceil(1.0F / perTick);
    }

    private static boolean canHarvest(ItemStack tool, BlockState state) {
        return !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
    }

    /** The block now at {@code c}'s cell, read where it lives (carriage plot or world). */
    private static BlockState currentState(ServerLevel level, DigCell c) {
        if (c.onCarriage()) return CarriageDeck.blockInPlot(c.subLevel.getPlot(), c.plotPos);
        return level.getBlockState(c.worldPos);
    }

    /** Carriages whose box (padded) holds the mob. */
    private List<ManagedShip> shipsNear(ServerLevel level) {
        List<ManagedShip> out = new ArrayList<>();
        double x = mob.getX(), y = mob.getY(), z = mob.getZ();
        for (Trains.Carriage c : Trains.allCarriages(level)) {
            if (!(c.ship() instanceof SableManagedShip sable) || sable.subLevel() == null) continue;
            AABBdc bb = c.ship().worldAABB();
            if (x < bb.minX() - CARRIAGE_PAD || x > bb.maxX() + CARRIAGE_PAD) continue;
            if (y < bb.minY() - CARRIAGE_PAD || y > bb.maxY() + CARRIAGE_PAD) continue;
            if (z < bb.minZ() - CARRIAGE_PAD || z > bb.maxZ() + CARRIAGE_PAD) continue;
            out.add(c.ship());
        }
        return out;
    }

    /**
     * Show (or with {@code stage < 0} clear) the crack on {@code c} to players near its world cell.
     * Sent directly: a carriage block's level position is in its far-off plot, where
     * {@code destroyBlockProgress}'s own range check would find nobody.
     */
    private void sendCrack(ServerLevel level, DigCell c, int stage) {
        Vec3 centre = c.worldCentre();
        ClientboundBlockDestructionPacket packet = new ClientboundBlockDestructionPacket(mob.getId(), c.levelPos(), stage);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(centre) < CRACK_RANGE_SQR) player.connection.send(packet);
        }
    }
}
