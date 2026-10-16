package games.brennan.dungeontrain.client.menu.number;

import net.minecraft.network.chat.Component;

/** The number pad's heading for each kind of number, from {@code gui.dungeontrain.number_input.<key>}. */
public final class NumberTitle {

    private NumberTitle() {}

    public static String of(String key) {
        return Component.translatable("gui.dungeontrain.number_input." + key).getString();
    }
}
