package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.photo.album.AlbumOwnership;
import games.brennan.dungeontrain.net.AlbumViewClosedPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import io.github.mortuusars.exposure.client.gui.screen.album.AlbumViewScreen;
import io.github.mortuusars.exposure.client.gui.screen.album.ChildPhotographScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Client-side close detection for found albums (another player's, read-only): when the player is
 * done looking through one, tell the server, which burns it.
 *
 * <p>Exposure's {@link AlbumViewScreen} is a pure-client screen, so the server never sees it close —
 * the same reason {@link DisposablePhotoClientEvents} exists. Unlike a photograph, an album hands off
 * to a child screen when a page is zoomed into, so a close only counts once the next tick shows
 * neither the album nor one of its photographs.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class FoundAlbumClientEvents {

    private static boolean pending;

    private FoundAlbumClientEvents() {}

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (!(event.getScreen() instanceof AlbumViewScreen)) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        if (AlbumOwnership.foundOwner(player.getMainHandItem()).isPresent()
                || AlbumOwnership.foundOwner(player.getOffhandItem()).isPresent()) {
            pending = true;
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!pending) return;
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof AlbumViewScreen || screen instanceof ChildPhotographScreen) return;
        pending = false;
        if (Minecraft.getInstance().player != null) DungeonTrainNet.sendToServer(new AlbumViewClosedPacket());
    }
}
