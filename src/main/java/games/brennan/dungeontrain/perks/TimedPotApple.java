package games.brennan.dungeontrain.perks;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.event.BoardingProgressEvents;
import games.brennan.dungeontrain.event.PlayerActivityTracker;
import games.brennan.dungeontrain.event.PortalCarriageEvents;
import games.brennan.dungeontrain.item.CosmicAppleItem;
import games.brennan.dungeontrain.ship.PlotWorldPositions;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.SharedCarriageRegistry;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * For every {@link #TICKS_PER_APPLE} a player spends on the train (dimensional carriages included, counted
 * as train-time stats count it: alive, not AFK or paused), one apple goes into the nearest decorated pot on
 * the train that player has not looked at yet. A pot counts as looked at once it has been within
 * {@link #SEE_DISTANCE} blocks, inside the player's view cone and in clear sight. The clock never resets on
 * a find; at most {@link #MAX_OWED} apples wait for a pot. Called once a second from {@code FirstAppleEvents}.
 */
public final class TimedPotApple {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Ten minutes of train time per apple. */
    static final int TICKS_PER_APPLE = 20 * 60 * 10;
    /** Most apples waiting for a pot. */
    static final int MAX_OWED = 2;
    /** Ticks between checks (the caller's cadence). */
    static final int STEP_TICKS = 20;
    /** A pot this close, in the cone and in clear sight, has been looked at. */
    static final double SEE_DISTANCE = 12.0;
    /** Half-angle of the view cone: about 40 degrees. */
    static final double SEE_COS_HALF_ANGLE = Math.cos(Math.toRadians(40));
    /** Pots this far from the player are candidates for the apple, and groups this close are scanned. */
    static final double REACH = 40.0;

    /** Train-time per apple; tests and harnesses may shorten it, never a shipped path. */
    static int ticksPerApple = TICKS_PER_APPLE;

    private TimedPotApple() {}

    /** Ticks toward the next apple and the apples owed. */
    public record Progress(long ticks, int owed) {}

    // ---- pure rules ----

    /** {@code ticks} towards the next apple and {@code owed} apples, after {@code add} more train ticks. */
    static Progress advance(long ticks, int owed, int add, int perApple) {
        long t = Math.max(0, ticks) + Math.max(0, add);
        int o = Math.max(0, owed);
        int per = Math.max(1, perApple);
        while (t >= per) {
            t -= per;
            o = Math.min(MAX_OWED, o + 1);
        }
        return new Progress(t, o);
    }

    /** True when {@code toTarget} (eye to block) is within {@code maxDist} and the cone around {@code look}. */
    static boolean inCone(Vec3 look, Vec3 toTarget, double maxDist, double cosHalfAngle) {
        double dist = toTarget.length();
        if (dist > maxDist) return false;
        if (dist < 1.0e-3) return true;
        double lookLen = look.length();
        if (lookLen < 1.0e-6) return false;
        return look.dot(toTarget) / (lookLen * dist) >= cosHalfAngle;
    }

    // ---- server ----

    private record Pot(DecoratedPotBlockEntity pot, Trains.Carriage group, Vec3 world) {}

    public static void tick(MinecraftServer server) {
        if (!CosmicAppleDrops.open(server)) return;
        TimedPotAppleState state = TimedPotAppleState.get(server);
        CarriageDims worldDims = DungeonTrainWorldData.get(server.overworld()).dims();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            ServerLevel level = player.serverLevel();
            List<Trains.Carriage> train = Trains.allCarriages(level);
            boolean boarded = BoardingProgressEvents.isBoarded(train, player);
            boolean inRoom = !boarded && PortalCarriageEvents.portalStructurePairKey(
                worldDims, player.getX(), player.getY(), player.getZ()) != null;
            if (!(boarded || inRoom) || !PlayerActivityTracker.isCountingTrain(player)) continue;
            UUID id = player.getUUID();
            TimedPotAppleState.Entry e = state.entry(id);
            if (state.setProgress(id, advance(e.ticks, e.owed, STEP_TICKS, ticksPerApple))) {
                LOGGER.info("[DT-TimedApple] {}: another {} s on the train, apples owed {}",
                    player.getScoreboardName(), ticksPerApple / 20, state.entry(id).owed);
            }
            if (!boarded) continue; // the apple waits until they are back on a carriage
            List<Pot> pots = potsNear(level, train, player);
            markSeen(level, player, pots, state);
            if (state.entry(id).owed > 0) deliver(level, player, pots, state);
        }
    }

    /** The pots in unshared carriages of the groups within {@link #REACH} of the player. */
    private static List<Pot> potsNear(ServerLevel level, List<Trains.Carriage> train, ServerPlayer player) {
        List<Pot> out = new ArrayList<>();
        Vec3 at = player.position();
        for (Trains.Carriage group : train) {
            AABBdc box = group.ship().worldAABB();
            double dx = Math.max(0, Math.max(box.minX() - at.x, at.x - box.maxX()));
            double dz = Math.max(0, Math.max(box.minZ() - at.z, at.z - box.maxZ()));
            if (dx * dx + dz * dz > REACH * REACH) continue;
            UUID subLevel = group.ship().subLevelId();
            for (DecoratedPotBlockEntity pot : CosmicAppleDrops.potsIn(level, groupBox(group))) {
                BlockPos p = pot.getBlockPos();
                if (SharedCarriageRegistry.resolve(subLevel, p.getX(), p.getY(), p.getZ()) != null) continue;
                out.add(new Pot(pot, group, PlotWorldPositions.worldCenter(level, p)));
            }
        }
        return out;
    }

    /** The group's carriages in plot space: the plot starts at the back pad, the carriages half a pad in. */
    private static BoundingBox groupBox(Trains.Carriage group) {
        int size = Math.max(1, group.provider().getGroupSize());
        CarriageDims dims = group.provider().dims();
        BlockPos start = group.provider().getShipyardOrigin().offset(size > 1 ? CarriagePlacer.halfPadLen(dims) : 0, 0, 0);
        return BoundingBox.fromCorners(start, start.offset(dims.length() * size - 1, dims.height(), dims.width()));
    }

    private static void markSeen(ServerLevel level, ServerPlayer player, List<Pot> pots, TimedPotAppleState state) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0f);
        for (Pot pot : pots) {
            if (!inCone(look, pot.world().subtract(eye), SEE_DISTANCE, SEE_COS_HALF_ANGLE)) continue;
            if (!clearSight(level, pot, eye)) continue;
            state.markSeen(player.getUUID(), new FirstAppleState.Spot(level.dimension(), pot.pot().getBlockPos()));
        }
    }

    /**
     * Nothing solid on the carriage between the eye and the pot. The ray runs in the carriage's plot space,
     * where its blocks are: a carriage never rotates (its rotation is locked to identity), so the eye sits
     * in plot space at the pot's plot centre plus the eye's world offset from the pot.
     */
    private static boolean clearSight(ServerLevel level, Pot pot, Vec3 eye) {
        BlockPos target = pot.pot().getBlockPos();
        Vec3 potPlot = Vec3.atCenterOf(target);
        Vec3 from = potPlot.add(eye.subtract(pot.world()));
        BlockHitResult hit = level.clip(new ClipContext(from, potPlot,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    private static void deliver(ServerLevel level, ServerPlayer player, List<Pot> pots, TimedPotAppleState state) {
        TimedPotAppleState.Entry e = state.entry(player.getUUID());
        Pot best = null;
        double bestSq = Double.MAX_VALUE;
        for (Pot pot : pots) {
            if (CosmicAppleItem.isCosmicApple(pot.pot().getTheItem())) continue;
            if (e.seen.contains(new FirstAppleState.Spot(level.dimension(), pot.pot().getBlockPos()))) continue;
            double sq = pot.world().distanceToSqr(player.position());
            if (sq <= REACH * REACH && sq < bestSq) {
                bestSq = sq;
                best = pot;
            }
        }
        if (best == null) return;
        int pIdx = best.group().provider().getPIdx();
        long index = player.getUUID().getLeastSignificantBits() ^ ((long) e.given << 32);
        ItemStack apple = CosmicAppleDrops.rollApple(level.getSeed(), index, DifficultyProgression.positionTier(pIdx));
        // A pot the first-apple boost once marked (its extra long gone) is ours now: unmark it, or the boost
        // would take this apple back out later.
        if (FirstAppleBoost.marker(best.pot()) != null) FirstAppleBoost.release(level, best.pot());
        best.pot().setTheItem(apple);
        best.pot().setChanged();
        state.delivered(player.getUUID());
        LOGGER.info("[DT-TimedApple] {}: {} in pot at {} ({} blocks away, unseen), owed now {}",
            player.getScoreboardName(), apple.getItem(), best.pot().getBlockPos(),
            String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(bestSq)), state.entry(player.getUUID()).owed);
    }
}
