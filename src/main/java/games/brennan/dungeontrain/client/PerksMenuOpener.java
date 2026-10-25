package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.client.perks.PerksScreen;
import net.minecraft.client.Minecraft;

/**
 * Client door into the perks menu, opened by the server after a cosmic apple is eaten
 * ({@link games.brennan.dungeontrain.net.OpenPerksMenuPacket}). Another screen already up (a book,
 * a chest) wins: the point stays banked and the menu waits for the P key.
 */
public final class PerksMenuOpener {

    private PerksMenuOpener() {}

    public static void open(int activePoints) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.screen != null && !(mc.screen instanceof PerksScreen)) return;
        PerksScreen.open(true);
    }
}
