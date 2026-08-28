package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PlayerPausedPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Tells the server when this client is sitting on the pause screen, so paused time stops counting
 * toward time on the train and run playtime (see
 * {@link games.brennan.dungeontrain.event.PlayerActivityTracker}).
 *
 * <p>Edge-triggered: the pause state is sampled each client tick but a
 * {@link PlayerPausedPacket} only goes out when it changes — one packet per pause, one per
 * un-pause. The state is reset when leaving a world so the next join starts un-paused.</p>
 *
 * <p>Only screens that declare themselves pausing count. DT's own menus deliberately return
 * {@code isPauseScreen() == false} (the world keeps running behind them on purpose); a player who
 * parks in one is caught by the tracker's five-minute idle rule instead.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PauseStateReporter {

    /** Last state sent to the server. Nothing is sent until this changes. */
    private static boolean reportedPaused = false;

    private PauseStateReporter() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getConnection() == null) {
            // Left the world — the server drops our state on logout; start clean on the next join.
            reportedPaused = false;
            return;
        }
        boolean paused = mc.screen != null && mc.screen.isPauseScreen();
        if (paused == reportedPaused) return;
        reportedPaused = paused;
        DungeonTrainNet.sendToServer(new PlayerPausedPacket(paused));
    }
}
