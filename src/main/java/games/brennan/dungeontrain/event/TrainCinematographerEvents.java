package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Vector3d;
import org.joml.primitives.AABBdc;

@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TrainCinematographerEvents {

    private static int tickCounter = 0;
    private static final int SCAN_PERIOD_TICKS = 2;

    private TrainCinematographerEvents() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (Math.floorMod(tickCounter++, SCAN_PERIOD_TICKS) != 0) return;

        for (ServerPlayer player : level.players()) {
            if (!CinematographerService.isActive(player.getUUID())) continue;
            int r = (int) CinematographerService.getDistance(player.getUUID());
            openNearbyDoors(level, player.blockPosition(), r);
            openNearbyDoorsOnShips(level, player, r);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CinematographerService.cleanup(player.getUUID());
        }
    }

    /**
     * Opens doors on Sable ships near the player. Mirrors SoulCampfireHealEvents:
     * find ships via Shipyards, convert player world pos to ship-local, then use
     * the ship's EmbeddedPlotLevelAccessor (which implements ServerLevelAccessor)
     * to read and set block states using local coordinates.
     */
    private static void openNearbyDoorsOnShips(ServerLevel level, ServerPlayer player, int r) {
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            AABBdc bb = ship.worldAABB();
            if (px < bb.minX() - r || px > bb.maxX() + r) continue;
            if (py < bb.minY() - r || py > bb.maxY() + r) continue;
            if (pz < bb.minZ() - r || pz > bb.maxZ() + r) continue;
            if (!(ship instanceof SableManagedShip sableShip)) continue;

            Vector3d local = new Vector3d(px, py, pz);
            ship.worldToShip(local);
            BlockPos shipCenter = BlockPos.containing(local.x, local.y, local.z);
            openNearbyDoors(sableShip.subLevel().getPlot().getEmbeddedLevelAccessor(), shipCenter, r);
        }
    }

    /**
     * Opens any closed door, trapdoor, or fence gate within {@code r} blocks of
     * {@code center}. Accepts any {@code ServerLevelAccessor} so it works for
     * both the main {@code ServerLevel} and Sable's {@code EmbeddedPlotLevelAccessor}
     * (ship-local coordinates).
     */
    private static void openNearbyDoors(ServerLevelAccessor level, BlockPos center, int r) {
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-r, -r, -r),
                center.offset(r, r, r))) {
            BlockState state = level.getBlockState(pos);
            if (!state.hasProperty(BlockStateProperties.OPEN)) continue;
            if (state.getValue(BlockStateProperties.OPEN)) continue;

            if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
                // Only trigger on the lower half to avoid processing the same door twice
                if (state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER) continue;
                BlockPos lowerPos = pos.immutable();
                level.setBlock(lowerPos, state.setValue(BlockStateProperties.OPEN, true), Block.UPDATE_ALL);
                // Explicitly sync upper half — DoorBlock.neighborChanged tracks redstone only, not OPEN
                BlockPos upperPos = lowerPos.above();
                BlockState upperState = level.getBlockState(upperPos);
                if (upperState.is(state.getBlock())
                        && upperState.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        && upperState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                        && upperState.hasProperty(BlockStateProperties.OPEN)
                        && !upperState.getValue(BlockStateProperties.OPEN)) {
                    level.setBlock(upperPos, upperState.setValue(BlockStateProperties.OPEN, true), Block.UPDATE_ALL);
                }
            } else {
                // Single-block openables: trapdoors, fence gates
                level.setBlock(pos.immutable(), state.setValue(BlockStateProperties.OPEN, true), Block.UPDATE_ALL);
            }
        }
    }
}
