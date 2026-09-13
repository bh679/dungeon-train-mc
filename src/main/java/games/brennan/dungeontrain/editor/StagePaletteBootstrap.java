package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Bakes the placeholder palette of every stage that has none at server start — before
 * {@code TrainBootstrapEvents} (priority LOW) stamps the starter train, so the first carriage
 * already resolves its stage placeholders through a real palette rather than the default one.
 * Idempotent: once {@code stages.json} carries palettes (the bundled file does, after a dev run
 * promoted them to source) this is a no-op.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class StagePaletteBootstrap {

    private StagePaletteBootstrap() {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onServerStarted(ServerStartedEvent event) {
        StagePaletteBaker.bakeMissing(event.getServer().overworld());
    }
}
