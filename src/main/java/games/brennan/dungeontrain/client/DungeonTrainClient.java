package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-side bootstrap. Registers the mod-list "settings" screen via
 * {@link IConfigScreenFactory}.
 *
 * <p>Kept separate from {@link DungeonTrain} so the dedicated server never
 * touches client-only types like {@code IConfigScreenFactory}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DungeonTrainClient {

    private DungeonTrainClient() {}

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        ModList.get().getModContainerById(DungeonTrain.MOD_ID).ifPresent(container ->
            container.registerExtensionPoint(
                IConfigScreenFactory.class,
                (mc, parent) -> new DungeonTrainSettingsScreen(parent)));
    }
}
