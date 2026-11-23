package games.brennan.dungeontrain.combat;

import com.mojang.logging.LogUtils;
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
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.event.EventHooks;
import org.joml.Vector3d;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A tool-carrying mob ({@link DifficultyApplier#DIG_TIER_KEY}) digging through to a target it has
 * seen. Runs when the mob has seen its target recently, has stopped closing the distance, and the
 * cells in front of it (head height, then feet, plus overhead when the target is above) are all
 * blocks it may break: {@link ToolTiers#mayBreak} at its break tier, never the floor. Floor is judged
 * by height, not block row: anything whose top is no higher than the mob could step ({@link #isFloor})
 * is ground it stands on or steps over, so carpet, slabs and paths at its feet are never in the way.
 * With no
 * path the navigator leaves a mob standing where it gave up, so when the way ahead is open ground
 * (or a one-block rise it can jump, {@link #isStepUp}) the goal first walks it straight at the target
 * until there is something to dig.
 *
 * <p>Nothing is dug while the navigator can still find a walkable path to the target: a mob that is
 * only circling or holding range in a fight is not stuck. A cell is in the way only when its collision
 * crosses the mob-wide lane down the middle of the cell ({@link #blocksLane}), so an open door, or a
 * closed one passed side-on, is not; carpet on the block in front ({@link #isLowProfile}) is never dug; and a
 * closed door that opens by hand is opened, as a player would, not mined.</p>
 *
 * <p>PlayerMobs always have this goal at {@link ToolTiers#MAX_TIER} (anything but floors), tool or
 * not: with no digging tool they mine at the speed of what is in their main hand, as a player would.</p>
 *
 * <p>Luminous Monsters' Miners always have this goal too, and may also break what their held tool can
 * harvest ({@link ToolTiers#minerMayBreak}). {@link #bestHand} still needs a digging tool, so a bow or an
 * empty hand digs nothing.</p>
 *
 * <p>Breaks one block at a time, taking as long as a player would with the same tool (tool speed,
 * Efficiency, Haste / Mining Fatigue, water and airborne penalties, right-tool 1/30 vs 1/100). A
 * carriage block is read and broken in its Sable plot ({@link CarriageDeck}); it drops at its world
 * cell through {@link PlayerBlockBreakOff#breakOff}, inside {@link MobBreakContext} so a shared
 * carriage does not upload the hole on its own.</p>
 */
public final class MobDigGoal extends Goal {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final int PRIORITY = 1;
    /** How long after last seeing its target a mob still digs toward it. */
    private static final int SEEN_MEMORY_TICKS = 200;
    /** No closer to the target for this long counts as stuck. */
    private static final int STUCK_TICKS = 40;
    /** Getting this much closer resets the stuck clock. */
    private static final double PROGRESS_EPS = 0.5;
    /** Within this, and in sight, the mob's own attack has it; no digging. */
    private static final double MELEE_RANGE = 2.0;
    private static final double MAX_TARGET_RANGE = 24.0;
    /** Max distance from the mob's eyes to the block centre while digging. */
    private static final double REACH = 3.0;
    /** Blocks that would take longer than this are not attempted. */
    private static final int MAX_DIG_TICKS = 30 * 20;
    /** After a break, the next cell may start straight away for this long. */
    private static final int CHAIN_TICKS = 20;
    private static final int SWING_EVERY_TICKS = 5;
    /** Longest straight-line walk toward the target looking for something to dig. */
    private static final int APPROACH_TICKS = 100;
    private static final int REPLAN_EVERY_TICKS = 5;
    private static final double CRACK_RANGE_SQR = 32.0 * 32.0;
    /** Padding around a carriage's box when looking for the carriage a cell belongs to. */
    private static final double CARRIAGE_PAD = 2.0;
    /** Highest rise (above the feet) a mob can jump onto rather than dig through. Vanilla jump is ~1.25. */
    private static final double JUMP_HEIGHT = 1.1;
    /** Collision no taller than this is a covering (carpet, thin snow) that needs a block under it. */
    private static final double LOW_PROFILE_HEIGHT = 0.125;
    /** Ticks to open a door in the way: a click, with a swing. */
    private static final int OPEN_DOOR_TICKS = 4;
    /** Approaches that ran out with no progress before a carpet in front is mined anyway. */
    private static final int FAILED_APPROACHES_BEFORE_CARPET = 2;
    /** Slack when judging whether a mob fits under a ceiling (a carriage reads a hair off the grid). */
    private static final double FIT_EPS = 1.0e-3;
    /** Luminous Monsters' Miners. They dig with a tool they hold, from any level, and can't without one. */
    private static final Set<String> MINER_IDS =
        Set.of("luminous_monsters:miner_zombie", "luminous_monsters:miner_skeleton");

    /**
     * One cell to break. A carriage cell keeps its ship and plot position (the world cell moves with
     * the train); a world cell has {@code ship == null}. A {@code floor} cell is ground the mob stands
     * on or can step over: never broken, and never in its way. {@code top} and {@code bottom} are the world
     * heights of the top and bottom of its collision; a {@code low} cell is a thin covering ({@link #isLowProfile}). A
     * {@code door} cell is a closed door that opens by hand: opened, not broken.
     */
    private record DigCell(BlockPos worldPos, ManagedShip ship, ServerSubLevel subLevel, BlockPos plotPos,
                           BlockState state, InteractionHand hand, int ticks, boolean floor, double top,
                           double bottom, boolean low, boolean door) {
        boolean onCarriage() { return ship != null; }
        BlockPos levelPos() { return onCarriage() ? plotPos : worldPos; }
        Vec3 worldCentre() {
            if (!onCarriage()) return Vec3.atCenterOf(worldPos);
            Vector3d c = ship.shipToWorld(new Vector3d(plotPos.getX() + 0.5, plotPos.getY() + 0.5, plotPos.getZ() + 0.5));
            return new Vec3(c.x, c.y, c.z);
        }
    }

    private final Mob mob;
    /** Digs with whatever is in the main hand when it holds no digging tool, at that item's speed. */
    private final boolean bareHanded;
    /** May also break what its held tool can harvest ({@link ToolTiers#minerMayBreak}), whatever its tier. */
    private final boolean miner;

    private int lastSeenTick = -SEEN_MEMORY_TICKS * 2;
    private LivingEntity trackedTarget;
    private double bestDistance = Double.MAX_VALUE;
    private int bestDistanceTick;
    private int chainUntilTick = Integer.MIN_VALUE;

    private DigCell cell;
    /** No cell to dig yet: walking straight at the target until one is in front. */
    private boolean approaching;
    private int approachTicks;
    /** Approaches in a row that timed out without the mob getting closer. */
    private int failedApproaches;
    private int progressTicks;
    private int lastStage;

    private MobDigGoal(Mob mob, boolean bareHanded, boolean miner) {
        this.mob = mob;
        this.bareHanded = bareHanded;
        this.miner = miner;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /**
     * Give {@code mob} its dig goal if it carries a break tier or is a Miner, and doesn't have one yet.
     * Safe to call from both the spawn roll and a load from disk.
     */
    public static void attach(Mob mob) {
        if (mob.level().isClientSide()) return;
        // PlayerMobs dig like players: always at the top tier, with whatever they hold.
        boolean playerMob = mob instanceof PlayerMobEntity;
        boolean miner = isMiner(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString());
        if (!playerMob && !miner && mob.getPersistentData().getInt(DifficultyApplier.DIG_TIER_KEY) <= ToolTiers.NO_TIER) return;
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof MobDigGoal) return;
        }
        mob.goalSelector.addGoal(PRIORITY, new MobDigGoal(mob, playerMob, miner));
    }

    static boolean isMiner(String entityId) {
        return MINER_IDS.contains(entityId);
    }

    /** Read live: a Miner's goal attaches at spawn, and its difficulty roll may give it a tier after that. */
    private int tier() {
        return bareHanded ? ToolTiers.MAX_TIER : mob.getPersistentData().getInt(DifficultyApplier.DIG_TIER_KEY);
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
            failedApproaches = 0;
        }
        boolean seen = mob.getSensing().hasLineOfSight(target);
        if (seen) lastSeenTick = now;
        if (now - lastSeenTick > SEEN_MEMORY_TICKS) return false;

        double distance = mob.distanceTo(target);
        if (distance > MAX_TARGET_RANGE) return false;
        // In reach and in sight: its own attack has it. In reach behind a wall still digs.
        if (seen && distance <= MELEE_RANGE) return false;
        if (distance < bestDistance - PROGRESS_EPS) {
            bestDistance = distance;
            bestDistanceTick = now;
            failedApproaches = 0;
        }
        if (now >= chainUntilTick && now - bestDistanceTick < STUCK_TICKS) return false;
        if (!EventHooks.canEntityGrief(level, mob)) return false;
        // Knocked back or mid-jump, the navigator can't plan at all: decide once it lands.
        if (!mob.onGround() && !mob.isInLiquid() && !mob.isPassenger()) return false;
        if (hasWalkablePath(target)) {
            // Not stuck, just fighting (circling, holding range): there is a way round, so dig nothing.
            LOGGER.debug("[DungeonTrain] MobDigGoal walkable path, no dig: mob={} at={}",
                    mob.getUUID(), mob.blockPosition());
            bestDistanceTick = now;
            chainUntilTick = Integer.MIN_VALUE;
            return false;
        }

        cell = planDig(level, target);
        // Walking at the gap has failed and failed again: a carpet in front is what is stopping it.
        if (cell == null && failedApproaches >= FAILED_APPROACHES_BEFORE_CARPET) cell = carpetInFront(level, target);
        approaching = cell == null && canApproach(level, target);
        if (cell == null && !approaching) {
            // Nothing breakable in the way: wait out another stuck window before looking again.
            bestDistanceTick = now;
            chainUntilTick = Integer.MIN_VALUE;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!(mob.level() instanceof ServerLevel level)) return false;
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) return false;
        if (approaching) return approachTicks < APPROACH_TICKS;
        if (cell == null) return false;
        if (!currentState(level, cell).equals(cell.state)) return false;
        if (!cell.door && !bareHanded && !(mob.getItemInHand(cell.hand).getItem() instanceof DiggerItem)) return false;
        return mob.getEyePosition().distanceTo(cell.worldCentre()) <= REACH;
    }

    @Override
    public void start() {
        mob.getNavigation().stop();
        approachTicks = 0;
        if (cell != null) beginDig();
    }

    private void beginDig() {
        progressTicks = 0;
        lastStage = -1;
        LivingEntity target = mob.getTarget();
        LOGGER.debug("[DungeonTrain] MobDigGoal start: mob={} tier={} block={} at={} carriage={} ticks={} mobY={} cellTop={} targetY={} onGround={}",
                mob.getUUID(), tier(), cell.state, cell.worldPos, cell.onCarriage(), cell.ticks,
                String.format("%.3f", mob.getY()), String.format("%.3f", cell.top),
                target == null ? "-" : String.format("%.3f", target.getY()), mob.onGround());
    }

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (approaching) {
            tickApproach(level);
            return;
        }
        if (cell == null) return;
        Vec3 centre = cell.worldCentre();
        mob.getLookControl().setLookAt(centre.x, centre.y, centre.z);
        if (progressTicks % SWING_EVERY_TICKS == 0) mob.swing(cell.hand);
        progressTicks++;
        int stage = Math.min(9, progressTicks * 10 / Math.max(1, cell.ticks));
        if (stage != lastStage && !cell.door) {
            sendCrack(level, cell, stage);
            lastStage = stage;
        }
        if (progressTicks >= cell.ticks) {
            if (cell.door) openDoor(level, cell);
            else finishBreak(level, cell);
        }
    }

    @Override
    public void stop() {
        if (approaching && approachTicks >= APPROACH_TICKS) failedApproaches++;
        if (cell != null && !cell.door && lastStage >= 0 && mob.level() instanceof ServerLevel level) {
            sendCrack(level, cell, -1);
        }
        cell = null;
        approaching = false;
        lastStage = -1;
    }

    /**
     * Walk straight at the target (no path exists, so the navigator would not move it) until a cell
     * it can dig is in front of it, then dig. Gives up when the way ahead stops being open ground.
     */
    private void tickApproach(ServerLevel level) {
        LivingEntity target = mob.getTarget();
        if (target == null) return;
        approachTicks++;
        if (approachTicks % REPLAN_EVERY_TICKS == 0) {
            DigCell next = planDig(level, target);
            if (next != null) {
                approaching = false;
                cell = next;
                mob.getNavigation().stop();
                beginDig();
                return;
            }
            if (!canApproach(level, target)) {
                approaching = false;
                return;
            }
        }
        mob.getMoveControl().setWantedPosition(target.getX(), mob.getY(), target.getZ(), 1.0);
        mob.getLookControl().setLookAt(target);
        // The wanted height is the mob's own, so MoveControl never jumps by itself: up a step it would stall.
        if (mob.onGround() && mob.horizontalCollision) mob.getJumpControl().jump();
    }

    /** Whether the cells in front are open and stand on solid ground, so walking on is safe. */
    private boolean canApproach(ServerLevel level, LivingEntity target) {
        BlockPos feet = mob.blockPosition();
        Direction dir = directionTo(target);
        BlockPos front = feet.relative(dir);
        List<ManagedShip> ships = shipsNear(level);
        DigCell foot = resolve(level, ships, front, dir);
        DigCell head = resolve(level, ships, front.above(), dir);
        if (isStepUp(level, ships, front, dir, foot, head)) return true;
        return isOpen(foot) && isOpen(head) && resolve(level, ships, front.below(), dir) != null;
    }

    /**
     * A rise the mob can jump onto instead of digging: the front cell tops out within
     * {@link #JUMP_HEIGHT} of its feet, with room above it (carpet on top is fine, it is floor).
     */
    private boolean isStepUp(ServerLevel level, List<ManagedShip> ships, BlockPos front, Direction dir,
                             DigCell foot, DigCell head) {
        if (foot == null || foot.floor) return false;
        double stand = head != null && head.low ? head.top : foot.top;
        if (!isStepUp(stand, mob.getY(), isOpen(head) || head.low, isOpen(resolve(level, ships, front.above(2), dir)))) {
            return false;
        }
        // Room to stand up on it: a low ceiling over the step is a wall, not a step.
        int fromRow = (head != null && head.low ? front.above() : front).getY() + 1;
        return fits(stand, mob.getBbHeight(), ceilingOver(level, ships, front, dir, fromRow, stand));
    }

    static boolean isStepUp(double footTopY, double mobY, boolean headOpen, boolean aboveOpen) {
        return headOpen && aboveOpen && footTopY <= mobY + JUMP_HEIGHT;
    }

    /**
     * Whether the navigator can find a walkable path all the way to {@code target}: one that reaches it,
     * or ends in its column within a block of its feet (a carriage deck reads a hair off the block grid).
     */
    private boolean hasWalkablePath(LivingEntity target) {
        Path path = mob.getNavigation().createPath(target, 0);
        if (path == null) return false;
        if (path.canReach()) return true;
        var node = path.getEndNode();
        if (node == null) return false;
        BlockPos at = target.blockPosition();
        return endsAtTarget(node.x, node.y, node.z, at.getX(), at.getY(), at.getZ());
    }

    /** A path's end node in the target's own column, within one block up or down of its feet. */
    static boolean endsAtTarget(int nodeX, int nodeY, int nodeZ, int targetX, int targetY, int targetZ) {
        return nodeX == targetX && nodeZ == targetZ && Math.abs(nodeY - targetY) <= 1;
    }

    /** Nothing in the way: no collision, or ground the mob can stand on or step over. */
    private static boolean isOpen(DigCell c) {
        return c == null || c.floor;
    }

    /** The horizontal direction from the mob toward {@code target}. */
    private Direction directionTo(LivingEntity target) {
        return Direction.getNearest(target.getX() - mob.getX(), 0, target.getZ() - mob.getZ());
    }

    /** Open a closed door in the way, as a player clicking it would; the next plan walks through. */
    private void openDoor(ServerLevel level, DigCell target) {
        if (target.state.getBlock() instanceof DoorBlock door) {
            // As a mob's change, like a dig: a shared carriage does not upload it as a player's edit.
            MobBreakContext.run(() -> {
                door.setOpen(mob, level, target.state, target.levelPos(), true);
                return true;
            });
            LOGGER.debug("[DungeonTrain] MobDigGoal open door: mob={} at={} carriage={}",
                    mob.getUUID(), target.worldPos, target.onCarriage());
            chainUntilTick = mob.tickCount + CHAIN_TICKS;
        }
        cell = null;
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
        LOGGER.debug("[DungeonTrain] MobDigGoal break: mob={} block={} at={} carriage={} broke={}",
                mob.getUUID(), target.state, target.worldPos, target.onCarriage(), broke);
        if (broke) {
            EquipmentSlot slot = target.hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
            ItemStack used = mob.getItemInHand(target.hand);
            if (used.getItem() instanceof DiggerItem) used.hurtAndBreak(1, mob, slot);
            chainUntilTick = mob.tickCount + CHAIN_TICKS;
            failedApproaches = 0;
        }
        cell = null;
    }

    /**
     * A carpet (any {@link #isLowProfile low-profile} block) in front that is really in the way: standing
     * on it the mob would not fit under the ceiling over it, but on the bare block it would. Carpet in a
     * low doorway, or on a step under a low lintel. Null when there is no such carpet, or it may not be
     * broken.
     */
    private DigCell carpetBlocking(ServerLevel level, List<ManagedShip> ships, BlockPos front, Direction dir,
                                   DigCell foot, DigCell head) {
        DigCell carpet;
        double bare;
        if (foot != null && foot.low && foot.floor) {
            carpet = foot;                       // on the floor in front
            bare = foot.bottom;
        } else if (head != null && head.low && !isOpen(foot)) {
            carpet = head;                       // on top of the step in front
            bare = foot.top;
        } else {
            return null;
        }
        double height = mob.getBbHeight();
        double ceiling = ceilingOver(level, ships, front, dir, carpet.worldPos.getY() + 1, carpet.top);
        if (!carpetIsInTheWay(carpet.top, bare, height, ceiling)) return null;
        return diggable(resolve(level, ships, carpet.worldPos, dir, true));
    }

    /**
     * Last resort, after walking at the gap has failed {@link #FAILED_APPROACHES_BEFORE_CARPET} times: a
     * carpet in front, on the floor or on a step, is mined. Covers what {@link #carpetBlocking} can't
     * predict from shapes.
     */
    private DigCell carpetInFront(ServerLevel level, LivingEntity target) {
        Direction dir = directionTo(target);
        BlockPos front = mob.blockPosition().relative(dir);
        List<ManagedShip> ships = shipsNear(level);
        for (BlockPos pos : List.of(front, front.above())) {
            DigCell c = diggable(resolve(level, ships, pos, dir, true));
            if (c != null && c.low) {
                LOGGER.debug("[DungeonTrain] MobDigGoal carpet fallback: mob={} block={} at={}", mob.getUUID(), c.state, c.worldPos);
                return c;
            }
        }
        return null;
    }

    private static DigCell diggable(DigCell c) {
        return c != null && !c.floor && c.ticks >= 0 ? c : null;
    }

    /**
     * World height of the lowest ceiling over the column at {@code front}, from row {@code fromRow} up to
     * the head of a mob standing at {@code standY}: the bottom of the first cell above that crosses its
     * lane. {@link Double#POSITIVE_INFINITY} when the way up is clear.
     */
    private double ceilingOver(ServerLevel level, List<ManagedShip> ships, BlockPos front, Direction dir,
                               int fromRow, double standY) {
        int toRow = (int) Math.floor(standY + mob.getBbHeight() - FIT_EPS);
        double ceiling = Double.POSITIVE_INFINITY;
        for (int row = fromRow; row <= toRow; row++) {
            DigCell c = resolve(level, ships, front.atY(row), dir);
            if (c != null && !c.floor) ceiling = Math.min(ceiling, c.bottom);
        }
        return ceiling;
    }

    /** Whether a mob {@code height} tall standing at {@code standY} fits under {@code ceilingY}. */
    static boolean fits(double standY, double height, double ceilingY) {
        return standY + height <= ceilingY + FIT_EPS;
    }

    /**
     * Whether a carpet topping out at {@code carpetTop}, on ground at {@code bareY}, is what stops a mob
     * {@code height} tall under {@code ceilingY}: it fits on the bare ground but not on the carpet.
     */
    static boolean carpetIsInTheWay(double carpetTop, double bareY, double height, double ceilingY) {
        return !fits(carpetTop, height, ceilingY) && fits(bareY, height, ceilingY);
    }

    /**
     * The first blocking cell between the mob and {@code target}, or null when there is none or any
     * blocking cell is one it may not break (it could not get through anyway).
     */
    private DigCell planDig(ServerLevel level, LivingEntity target) {
        BlockPos feet = mob.blockPosition();
        boolean targetAbove = target.getY() - mob.getY() > 1.5;
        Direction dir = directionTo(target);
        BlockPos front = feet.relative(dir);

        List<ManagedShip> ships = shipsNear(level);
        DigCell foot = resolve(level, ships, front, dir);
        DigCell head = resolve(level, ships, front.above(), dir);
        if (!targetAbove && isStepUp(level, ships, front, dir, foot, head)) return null;
        DigCell carpet = carpetBlocking(level, ships, front, dir, foot, head);
        if (carpet != null) return carpet;

        List<DigCell> candidates = new ArrayList<>(3);
        if (targetAbove) candidates.add(resolve(level, ships, feet.above(2), null));
        // Carpet and the like on the block in front is not in the way: that block is, and it falls with it.
        if (!(head != null && head.low && !isOpen(foot))) candidates.add(head);
        candidates.add(foot);

        DigCell first = null;
        for (DigCell c : candidates) {
            if (isOpen(c)) continue;           // open, or floor: nothing to break here
            if (c.ticks < 0) return null;      // blocking and not ours to break
            if (first == null) first = c;
        }
        return first;
    }

    /**
     * The cell at world {@code pos} for a mob moving {@code travel} (null: straight up): null when open
     * or its collision is clear of the mob's lane, a cell with {@code ticks < 0} when it blocks but
     * may not be broken (always so for {@link DigCell#floor floor}), otherwise the cell with its hand
     * and dig time.
     */
    private DigCell resolve(ServerLevel level, List<ManagedShip> ships, BlockPos pos, Direction travel) {
        return resolve(level, ships, pos, travel, false);
    }

    /**
     * As {@link #resolve(ServerLevel, List, BlockPos, Direction)}; with {@code digLow}, a low-profile cell
     * that would be floor (carpet the mob stands on) comes back diggable instead, for when it is what
     * stops the mob ({@link #carpetBlocking}, {@link #carpetInFront}).
     */
    private DigCell resolve(ServerLevel level, List<ManagedShip> ships, BlockPos pos, Direction travel, boolean digLow) {
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
        VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (shape.isEmpty()) return null;
        if (!blocksLane(shape, laneAxis(travel, ship, pos, plotPos), mob.getBbWidth())) return null;

        double top = cellTopY(pos, ship, plotPos, shape);
        double bottom = cellY(pos, ship, plotPos, shape.min(Direction.Axis.Y));
        boolean low = isLowProfile(shape.max(Direction.Axis.Y));
        boolean floor = isFloor(top, mob.getY(), mob.maxUpStep()) && !(digLow && low);
        DigCell blocked = new DigCell(pos, ship, subLevel, plotPos, state, InteractionHand.MAIN_HAND, -1, floor, top, bottom, low, false);
        if (floor) return blocked;
        if (state.getBlock() instanceof DoorBlock door && door.type().canOpenByHand()
                && !state.getValue(DoorBlock.OPEN)) {
            return new DigCell(pos, ship, subLevel, plotPos, state, InteractionHand.MAIN_HAND, OPEN_DOOR_TICKS,
                    false, top, bottom, low, true);
        }
        float hardness = state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (hardness < 0 || state.hasBlockEntity()) return blocked;

        InteractionHand hand = bestHand(state);
        if (hand == null) return blocked;
        ItemStack tool = mob.getItemInHand(hand);
        boolean playerPlaced = subLevel != null && PlayerPlacedTrainBlocks.isPlayerPlaced(subLevel, plotPos);
        boolean correctTool = tool.isCorrectToolForDrops(state);
        if (!ToolTiers.mayBreak(tier(), playerPlaced, correctTool, floor)
                && !(miner && ToolTiers.minerMayBreak(correctTool, floor))) return blocked;

        BlockPos levelPos = plotPos != null ? plotPos : pos;
        if (!EventHooks.onEntityDestroyBlock(mob, levelPos, state)) return blocked;

        int ticks = digTicks(level, tool, state, hardness);
        if (ticks > MAX_DIG_TICKS) return blocked;
        return new DigCell(pos, ship, subLevel, plotPos, state, hand, ticks, false, top, bottom, low, false);
    }

    /**
     * The horizontal axis the mob moves along through the cell, in the block's own frame (the carriage
     * plot for a carriage cell, which may sit turned against the world), or null when moving vertically.
     */
    private static Direction.Axis laneAxis(Direction travel, ManagedShip ship, BlockPos pos, BlockPos plotPos) {
        if (travel == null) return null;
        if (ship == null) return travel.getAxis();
        BlockPos behind = CarriageDeck.shipLocal(ship, pos.relative(travel.getOpposite()));
        int dx = plotPos.getX() - behind.getX(), dz = plotPos.getZ() - behind.getZ();
        if (dx == 0 && dz == 0) return null;
        return Math.abs(dx) >= Math.abs(dz) ? Direction.Axis.X : Direction.Axis.Z;
    }

    /**
     * Whether a cell's collision {@code shape} (block-local) crosses the lane a mob {@code width} wide
     * walks down the middle of the cell along {@code travel}. An open door or trapdoor, or a closed door
     * passed side-on, sits against a side of the cell and leaves the lane clear. With no travel axis
     * (moving vertically) any collision blocks.
     */
    static boolean blocksLane(VoxelShape shape, Direction.Axis travel, double width) {
        if (shape.isEmpty()) return false;
        if (travel == null || travel == Direction.Axis.Y) return true;
        double lo = Math.max(0.0, 0.5 - width / 2), hi = Math.min(1.0, 0.5 + width / 2);
        VoxelShape lane = travel == Direction.Axis.X
                ? Shapes.box(0, 0, lo, 1, 1, hi)
                : Shapes.box(lo, 0, 0, hi, 1, 1);
        return Shapes.joinIsNotEmpty(shape, lane, BooleanOp.AND);
    }

    /**
     * Whether a block is a thin covering ({@link #LOW_PROFILE_HEIGHT}: carpet, a thin snow layer). Lying
     * on the block in front it is not what stops a mob: that block is, and the covering falls with it.
     */
    static boolean isLowProfile(double shapeMaxY) {
        return shapeMaxY <= LOW_PROFILE_HEIGHT;
    }

    /**
     * Whether a cell whose collision top is at world height {@code cellTopY} is floor to a mob whose
     * feet are at {@code mobY}: no higher than it could step, so it stands on it or walks over it.
     * Judged by height rather than block row, since carpet, a bottom slab or a path shares the row of
     * the feet standing on it, and a carriage deck sits at whatever height the train does.
     */
    static boolean isFloor(double cellTopY, double mobY, double stepHeight) {
        return cellTopY <= mobY + stepHeight;
    }

    /** World height of the top of {@code shape} at the cell: in the carriage plot for a carriage cell. */
    private static double cellTopY(BlockPos pos, ManagedShip ship, BlockPos plotPos, VoxelShape shape) {
        return cellY(pos, ship, plotPos, shape.max(Direction.Axis.Y));
    }

    /** World height of block-local height {@code local} in the cell: in the carriage plot for a carriage cell. */
    private static double cellY(BlockPos pos, ManagedShip ship, BlockPos plotPos, double local) {
        if (ship == null) return pos.getY() + local;
        Vector3d world = ship.shipToWorld(new Vector3d(plotPos.getX() + 0.5, plotPos.getY() + local, plotPos.getZ() + 0.5));
        return world.y;
    }

    /**
     * The held digging tool that breaks {@code state} best: a right tool first, then the faster. With
     * none, a bare-handed digger uses its main hand; anyone else has nothing to dig with.
     */
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
        return best == null && bareHanded ? InteractionHand.MAIN_HAND : best;
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
