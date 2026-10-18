package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.CreakingHeartMenuGate;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Server half of the creaking heart settings click: swallow it so nothing is placed. */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class CreakingHeartMenuEvents {

    private CreakingHeartMenuEvents() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        CreakingHeartMenuGate.claim(event);
    }
}
