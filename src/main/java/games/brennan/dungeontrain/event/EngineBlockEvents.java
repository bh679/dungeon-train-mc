package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.block.engine.EngineBlock;
import games.brennan.dungeontrain.block.engine.EngineBlockEntity;
import games.brennan.dungeontrain.block.engine.EngineFuelTracker;
import games.brennan.dungeontrain.train.SharedBuild;
import games.brennan.dungeontrain.train.SharedCarriageRegistry;
import games.brennan.dungeontrain.train.SharedRoomRegistry;
import games.brennan.dungeontrain.train.engine.DriftEngine;
import games.brennan.dungeontrain.train.engine.EngineConversion;
import games.brennan.dungeontrain.train.engine.EngineStamp;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Setting a Drifting Engine Block down. In a drifting build a new one replaces a broken engine (one per
 * build; a second is refused). In an ordinary carriage it makes the carriage drift, with the three coal
 * any fresh drifting carriage starts with — see {@link EngineConversion}. Anyone may break one; it mines
 * as slowly as obsidian.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EngineBlockEvents {

    private EngineBlockEvents() {}

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getPlacedBlock().getBlock() instanceof EngineBlock)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = event.getPos();
        SharedBuild build = EngineFuelTracker.buildAt(level, pos);
        if (build != null) {
            if (build.isCulled()) return;
            if (hasOtherEngine(level, build, pos)) {
                event.setCanceled(true);
                if (event.getEntity() instanceof ServerPlayer player) {
                    player.displayClientMessage(
                            Component.translatable("message.dungeontrain.engine_block.second"), true);
                }
                return;
            }
            build.setFuel(coalAt(level, pos));
            return;
        }
        if (EngineConversion.convert(level, pos, DriftEngine.FRESH_COAL) != null
                && level.getBlockEntity(pos) instanceof EngineBlockEntity engine) {
            engine.setCoal(DriftEngine.FRESH_COAL);
        }
    }

    private static int coalAt(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof EngineBlockEntity engine ? engine.coal() : 0;
    }

    /** Whether the build already has an engine somewhere other than {@code pos}. */
    private static boolean hasOtherEngine(ServerLevel level, SharedBuild build, BlockPos pos) {
        BlockPos origin;
        Vec3i size;
        if (build instanceof SharedCarriageRegistry.Instance c) {
            origin = c.shipyardOrigin;
            size = EngineStamp.size(c.dims);
        } else if (build instanceof SharedRoomRegistry.Instance r) {
            origin = r.roomOrigin();
            size = r.size;
        } else {
            return false;
        }
        return EngineStamp.countEngines(level, origin, size, pos) > 0;
    }
}
