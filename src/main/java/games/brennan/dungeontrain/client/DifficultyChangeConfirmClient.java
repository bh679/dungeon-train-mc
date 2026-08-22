package games.brennan.dungeontrain.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.Difficulty;

/**
 * Client-side entry point for the difficulty-change confirmation. The server holds the change and
 * asks; this opens the prompt over whatever screen the player was on (normally the Options screen),
 * remembering it so cancelling puts them back where they were.
 *
 * <p>No opt-out: unlike the Free Play prompt this one is rare and costs the player their whole
 * loadout if they get it wrong.</p>
 */
public final class DifficultyChangeConfirmClient {

    private DifficultyChangeConfirmClient() {}

    public static void onShow(Difficulty from, Difficulty to, boolean targetEmpty) {
        Minecraft mc = Minecraft.getInstance();
        Screen current = mc.screen;
        // Cycling the Difficulty button twice sends two changes: keep the screen the player actually
        // came from rather than stacking prompts on top of each other.
        Screen parent = current instanceof DifficultyChangeConfirmScreen confirm ? confirm.parent() : current;
        mc.setScreen(new DifficultyChangeConfirmScreen(parent, from, to, targetEmpty));
    }
}
