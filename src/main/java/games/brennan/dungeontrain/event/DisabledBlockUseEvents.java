package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.DisabledBlockUseGate;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Both sides swallow a click on a disabled mod block, so the client doesn't predict a menu or arm swing. */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DisabledBlockUseEvents {

    private DisabledBlockUseEvents() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        DisabledBlockUseGate.veto(event);
    }
}
