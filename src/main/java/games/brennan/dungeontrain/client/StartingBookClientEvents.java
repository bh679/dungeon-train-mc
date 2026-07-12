package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.narrative.BurnableBookTag;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.StartingBookClosedPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.LecternScreen;

/**
 * Client-side close detection for burnable books (starting books, random
 * books from train chests, player-written books the signer kept, discovered
 * community books, and narrative "story" books read by hand — see
 * {@link games.brennan.dungeontrain.narrative.NarrativeBookTag}).
 *
 * <p>Vanilla {@code BookViewScreen} is a pure-client screen — opening or
 * closing a written book never reaches the server (the screen consumes the
 * stack locally via {@code BookViewScreen.BookAccess.fromItem}). To trigger
 * the drop-and-burn flow when the player closes the screen, we hook
 * {@link ScreenEvent.Closing} on the client and send a custom
 * {@link StartingBookClosedPacket} when the closing screen is a
 * {@link BookViewScreen} AND the local player has a burnable book in
 * <em>hand</em>.</p>
 *
 * <p>"Burnable" is decided by {@link BurnableBookTag#isBurnable} — see that
 * class for the full, current set of burnable tag types. The packet name is
 * historical; it now signals "any burnable book closed", and the server scans
 * all of them.</p>
 *
 * <p><b>{@link LecternScreen} is excluded</b> — it's a {@code BookViewScreen}
 * subclass (see {@code LecternScreen} hierarchy; also excluded the same way
 * in {@link games.brennan.dungeontrain.client.BookReadClientEvents}), but the
 * book it shows lives in the lectern's block entity, not a hand slot. Without
 * this exclusion, closing a lectern read would incorrectly check — and
 * potentially burn — an unrelated burnable book sitting in the player's
 * hotbar. Reading a narrative book in place at a lectern must never burn it.</p>
 *
 * <p>Detection scope: <b>mainhand + offhand only</b> — not the full
 * inventory. Vanilla written books can only be opened via right-click on
 * an in-hand stack, so the book the player just closed must still be in
 * a hand slot. Checking the full inventory would falsely trigger the burn
 * if the player closed a different written book while a burnable book was
 * tucked away in the hotbar.</p>
 */
public final class StartingBookClientEvents {

    private StartingBookClientEvents() {}

    public static void onScreenClosing(net.minecraft.client.gui.screens.Screen screen) {
        if (!(screen instanceof BookViewScreen) || screen instanceof LecternScreen) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        if (!BurnableBookTag.isBurnable(player.getMainHandItem())
            && !BurnableBookTag.isBurnable(player.getOffhandItem())) {
            return;
        }
        DungeonTrainNet.sendToServer(new StartingBookClosedPacket());
    }
}
