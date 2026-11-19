package games.brennan.dungeontrain.event;

import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.ship.CarriageDeck;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import games.brennan.dungeontrain.train.DeckLandingCell;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import org.joml.Vector3d;
import org.joml.primitives.AABBdc;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Lets endermen teleport onto the train.
 *
 * <p>Vanilla {@code EnderMan.teleport(x, y, z)} walks its target down until the level reports a
 * block that blocks motion, and {@code randomTeleport} checks the ground the same way. Carriage
 * blocks exist only in the Sable plot ({@code level.getBlockState} reads them as air, see
 * {@link CarriageDeck#blockAt}), so a target over the train always fell through to the ground under
 * it: an enderman never landed on the roof or in a carriage, not even when teleporting towards a
 * player on board who stared at it.</p>
 *
 * <p>This hooks the teleport event, which fires after that ground walk. When the target's X/Z is
 * over a carriage, the enderman is moved to a standable cell of that carriage instead (roof or
 * floor, see {@link DeckLandingCell}) and vanilla's own move is cancelled.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EndermanTrainTeleportEvents {

    /** Endermen are 2.9 tall: the feet cell and the two above it must be clear. */
    private static final int CLEAR_CELLS = 3;

    /**
     * Game time each enderman was last moved onto the train. A projectile hit retries
     * {@code teleport()} up to 64 times while it returns false, and a cancelled event returns false,
     * so later attempts in the same tick are cancelled without moving it again.
     */
    private static final Map<Entity, Long> LANDED_AT = new WeakHashMap<>();

    private EndermanTrainTeleportEvents() {}

    @SubscribeEvent
    public static void onEnderTeleport(EntityTeleportEvent.EnderEntity event) {
        if (!(event.getEntityLiving() instanceof EnderMan enderman)) return;
        if (!(enderman.level() instanceof ServerLevel level)) return;

        long now = level.getGameTime();
        Long landed = LANDED_AT.get(enderman);
        if (landed != null && landed == now) {
            event.setCanceled(true);
            return;
        }

        Optional<Vec3> landing = landingOnTrain(level, event.getTargetX(), event.getTargetZ(), enderman.getY());
        if (landing.isEmpty()) return;

        event.setCanceled(true);
        moveTo(level, enderman, landing.get());
        LANDED_AT.put(enderman, now);
    }

    /** World feet position on the carriage under target X/Z, or empty when no carriage is there. */
    private static Optional<Vec3> landingOnTrain(ServerLevel level, double x, double z, double fromY) {
        for (Trains.Carriage c : Trains.allCarriages(level)) {
            ManagedShip ship = c.ship();
            AABBdc bb = ship.worldAABB();
            double pad = CarriageDeck.HORIZONTAL_PADDING;
            if (x < bb.minX() - pad || x > bb.maxX() + pad) continue;
            if (z < bb.minZ() - pad || z > bb.maxZ() + pad) continue;
            if (!(ship instanceof SableManagedShip sable) || sable.subLevel() == null) continue;

            Optional<Vec3> spot = landingOnCarriage(ship, sable.subLevel().getPlot(), bb, x, z, fromY);
            if (spot.isPresent()) return spot;
        }
        return Optional.empty();
    }

    private static Optional<Vec3> landingOnCarriage(ManagedShip ship, LevelPlot plot, AABBdc bb,
                                                    double x, double z, double fromY) {
        Vector3d bottom = ship.worldToShip(new Vector3d(x, bb.minY(), z));
        Vector3d top = ship.worldToShip(new Vector3d(x, bb.maxY(), z));
        Vector3d prefer = ship.worldToShip(new Vector3d(x, fromY, z));
        int minY = (int) Math.floor(Math.min(bottom.y, top.y));
        int maxY = (int) Math.floor(Math.max(bottom.y, top.y)) + 1;
        BlockPos centre = BlockPos.containing(bottom.x, 0, bottom.z);

        return DeckLandingCell.find(centre, minY, maxY, (int) Math.floor(prefer.y), cell -> isStandable(plot, cell))
            .map(cell -> {
                Vector3d world = ship.shipToWorld(new Vector3d(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5));
                return new Vec3(world.x, world.y, world.z);
            });
    }

    /** Solid footing below, and air (no fluid) for the enderman's whole height. */
    private static boolean isStandable(LevelPlot plot, BlockPos feet) {
        if (!hasCollision(CarriageDeck.blockInPlot(plot, feet.below()))) return false;
        for (int i = 0; i < CLEAR_CELLS; i++) {
            BlockState s = CarriageDeck.blockInPlot(plot, feet.above(i));
            if (hasCollision(s) || !s.getFluidState().isEmpty()) return false;
        }
        return true;
    }

    private static boolean hasCollision(BlockState state) {
        return !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
    }

    /** What vanilla's successful {@code EnderMan.teleport} does, at our position. */
    private static void moveTo(ServerLevel level, EnderMan enderman, Vec3 to) {
        Vec3 from = enderman.position();
        enderman.teleportTo(to.x, to.y, to.z);
        enderman.resetFallDistance();
        enderman.getNavigation().stop();
        level.gameEvent(GameEvent.TELEPORT, from, GameEvent.Context.of(enderman));
        level.broadcastEntityEvent(enderman, (byte) 46);
        if (!enderman.isSilent()) {
            level.playSound(null, from.x, from.y, from.z, SoundEvents.ENDERMAN_TELEPORT, enderman.getSoundSource(), 1.0F, 1.0F);
            enderman.playSound(SoundEvents.ENDERMAN_TELEPORT, 1.0F, 1.0F);
        }
    }
}
