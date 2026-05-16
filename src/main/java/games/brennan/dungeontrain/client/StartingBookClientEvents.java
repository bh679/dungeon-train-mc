package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.narrative.StartingBookTag;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.StartingBookClosedPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Client-side close detection for starting books.
 *
 * <p>Vanilla {@code BookViewScreen} is a pure-client screen — opening or
 * closing a written book never reaches the server (the screen consumes the
 * stack locally via {@code BookViewScreen.BookAccess.fromItem}). To trigger
 * the drop-and-burn flow when the player closes the screen, we hook
 * {@link ScreenEvent.Closing} on the client and send a custom
 * {@link StartingBookClosedPacket} when the closing screen is a
 * {@link BookViewScreen} AND the local player has a stamped starting book
 * in <em>hand</em>.</p>
 *
 * <p>Detection scope: <b>mainhand + offhand only</b> — not the full
 * inventory. Vanilla written books can only be opened via right-click on
 * an in-hand stack, so the book the player just closed must still be in
 * a hand slot. Checking the full inventory would falsely trigger the burn
 * if the player closed a different written book while a starting book was
 * tucked away in the hotbar.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class StartingBookClientEvents {

    private StartingBookClientEvents() {}

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (!(event.getScreen() instanceof BookViewScreen)) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        if (!StartingBookTag.isStartingBook(player.getMainHandItem())
            && !StartingBookTag.isStartingBook(player.getOffhandItem())) {
            return;
        }
        DungeonTrainNet.sendToServer(new StartingBookClosedPacket());
    }
}
