package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TrainCinematographerEvents {

    private static int tickCounter = 0;
    private static final int SCAN_PERIOD_TICKS = 2;

    private TrainCinematographerEvents() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        tickCounter++;
        if (tickCounter % SCAN_PERIOD_TICKS != 0) return;

        for (ServerPlayer player : level.players()) {
            if (!CinematographerService.isActive(player.getUUID())) continue;
            int r = (int) CinematographerService.getDistance(player.getUUID());
            openNearbyDoors(level, player.blockPosition(), r);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CinematographerService.cleanup(player.getUUID());
        }
    }

    private static void openNearbyDoors(ServerLevel level, BlockPos center, int r) {
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-r, -r, -r),
                center.offset(r, r, r))) {
            BlockState state = level.getBlockState(pos);
            if (!state.hasProperty(BlockStateProperties.OPEN)) continue;
            if (state.getValue(BlockStateProperties.OPEN)) continue;

            if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
                // Only trigger on the lower half to avoid processing the same door twice
                if (state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER) continue;
                level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, true), Block.UPDATE_ALL);
                // Explicitly sync upper half — neighborChanged only tracks redstone, not OPEN state
                BlockPos upperPos = pos.above();
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
                level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, true), Block.UPDATE_ALL);
            }
        }
    }
}
