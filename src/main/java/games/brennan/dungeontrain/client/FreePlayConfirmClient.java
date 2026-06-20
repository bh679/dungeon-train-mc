package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.FreePlayConfirmResponsePacket;
import net.minecraft.client.Minecraft;

/**
 * Client-side entry point for the Free Play confirmation prompt. The server holds
 * the triggering action and asks; the client decides whether to surface the
 * screen or — if the player ticked "Don't show this again" — silently confirm so
 * the action proceeds with no interruption.
 */
public final class FreePlayConfirmClient {

    private FreePlayConfirmClient() {}

    public static void onShow(String label) {
        if (ClientDisplayConfig.isFreePlayConfirmOptedOut()) {
            DungeonTrainNet.sendToServer(new FreePlayConfirmResponsePacket(true));
            return;
        }
        Minecraft.getInstance().setScreen(new FreePlayConfirmScreen(label));
    }
}
