package games.brennan.dungeontrain.platform.neoforge;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.platform.event.DtClientTickCallback;
import games.brennan.dungeontrain.platform.event.DtEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Thin NeoForge → {@code DtEvents} bridge for client tick events. Client-only
 * ({@code value = Dist.CLIENT}) so the class never loads on a dedicated server.
 * Subscribes {@code ClientTickEvent.Pre}/{@code .Post} once each and fires the
 * matching {@code DtEvents} field in registration order — pure passthrough, no
 * logic.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class NeoForgeClientTickBridge {

    private NeoForgeClientTickBridge() {}

    @SubscribeEvent
    public static void onPre(ClientTickEvent.Pre event) {
        for (DtClientTickCallback cb : DtEvents.CLIENT_TICK_PRE.listeners()) {
            cb.onClientTick();
        }
    }

    @SubscribeEvent
    public static void onPost(ClientTickEvent.Post event) {
        for (DtClientTickCallback cb : DtEvents.CLIENT_TICK_POST.listeners()) {
            cb.onClientTick();
        }
    }
}
