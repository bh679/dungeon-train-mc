package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.block.engine.EngineBlock;
import games.brennan.dungeontrain.block.engine.EngineBlockEntity;
import games.brennan.dungeontrain.block.engine.EngineFuelTracker;
import games.brennan.dungeontrain.train.SharedBuild;
import games.brennan.dungeontrain.train.engine.DriftEngine;
import games.brennan.dungeontrain.train.engine.EngineConversion;
import games.brennan.dungeontrain.train.engine.EngineStamp;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Setting a Drifting Engine Block down. In a drifting build it joins the engines already there (any
 * number; the build's fuel is their total, burnt one engine at a time). In an ordinary carriage it makes
 * the carriage drift, with the three coal any fresh drifting carriage starts with — see
 * {@link EngineConversion}. Anyone may break one; it mines as slowly as obsidian.
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
            int total = EngineStamp.totalCoal(level, build, null);
            if (total >= 0) build.setFuel(total);
            return;
        }
        if (EngineConversion.convert(level, pos, DriftEngine.FRESH_COAL) != null
                && level.getBlockEntity(pos) instanceof EngineBlockEntity engine) {
            engine.setCoal(DriftEngine.FRESH_COAL);
        }
    }
}
