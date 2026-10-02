package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.DisposableCamera;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PhotographViewClosedPacket;
import io.github.mortuusars.exposure.client.gui.screen.PhotographScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Client-side close detection for disposable-camera photographs.
 *
 * <p>Exposure's {@link PhotographScreen} is a pure-client screen, like vanilla's book view, so the
 * server never hears that a photo was looked at. Same shape as {@link StartingBookClientEvents}: on
 * the screen closing, if a hand holds a photo that burns after viewing, tell the server.</p>
 *
 * <p>Hands only — a photograph opens from the hand, so a flagged photo elsewhere in the inventory was
 * not the one just viewed.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DisposablePhotoClientEvents {

    private DisposablePhotoClientEvents() {}

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (!(event.getScreen() instanceof PhotographScreen)) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        if (!DisposableCamera.holdsBurnAfterViewing(player.getMainHandItem())
            && !DisposableCamera.holdsBurnAfterViewing(player.getOffhandItem())) {
            return;
        }
        DungeonTrainNet.sendToServer(new PhotographViewClosedPacket());
    }
}
