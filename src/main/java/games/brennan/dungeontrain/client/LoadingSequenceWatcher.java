package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Closes out the world-join loading sequence — the point after which
 * {@link LoadingSequenceProgress#isJoining()} is false and DT stops theming vanilla's
 * {@code ReceivingLevelScreen} (which is reused later for portal travel and dimension changes).
 *
 * <p>The end of the join is "the player is in the world with nothing in front of them": no
 * screen up and {@link CinematicPreloadGate} no longer holding. That is reached on every join
 * path, including worlds that never arm the cinematic gate, so the flag can't get stuck on.
 * {@code ClientPlayerNetworkEvent.LoggingOut} resets the timeline for the next join
 * (see {@link CinematicPreloadGate#onLoggingOut}).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LoadingSequenceWatcher {

    private LoadingSequenceWatcher() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!LoadingSequenceProgress.isJoining()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.screen != null || CinematicPreloadGate.isActive()) return;
        LoadingSequenceProgress.finishJoin();
    }
}
