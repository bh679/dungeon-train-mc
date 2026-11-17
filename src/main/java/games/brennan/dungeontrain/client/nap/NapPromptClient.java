package games.brennan.dungeontrain.client.nap;

import net.minecraft.client.Minecraft;

/** Opens the nap prompt the server asked for ({@code ShowNapPromptPacket}). */
public final class NapPromptClient {

    private NapPromptClient() {}

    public static void onShow(boolean night) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.setScreen(new NapPromptScreen(night));
    }
}
