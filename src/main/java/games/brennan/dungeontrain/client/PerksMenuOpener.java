package games.brennan.dungeontrain.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Client door into the perks menu. Until the menu screen lands this is a stub that says a point is
 * ready; the menu work replaces the body with {@code Minecraft.getInstance().setScreen(...)}.
 */
public final class PerksMenuOpener {

    private PerksMenuOpener() {}

    public static void open(int activePoints) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.displayClientMessage(Component.translatable("chat.dungeontrain.perks.ready_to_spend", activePoints)
            .withStyle(ChatFormatting.LIGHT_PURPLE), false);
    }
}
