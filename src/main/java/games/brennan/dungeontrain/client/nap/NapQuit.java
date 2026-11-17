package games.brennan.dungeontrain.client.nap;

import games.brennan.dungeontrain.client.DeathScreenLayoutHandler;
import games.brennan.dungeontrain.client.NarrativeDeathScreen;
import net.minecraft.client.Minecraft;

/**
 * The client half of taking a nap: the server has saved it ({@code NapSavedPacket}), so the player
 * gets the death screen's feedback questions and Contribute page ({@link NarrativeDeathScreen#afterNap}),
 * then leaves to the title screen with the world deleted. The nap waits in its slot (Free Play or
 * Survival, and difficulty) until a new run of the same kind starts.
 */
public final class NapQuit {

    private NapQuit() {}

    /** The nap is saved: show the farewell pages, which leave through {@link #leaveNow}. */
    public static void leave(String levelId) {
        Minecraft.getInstance().setScreen(NarrativeDeathScreen.afterNap(levelId));
    }

    /** Leave the napped run for the title screen, deleting its world. */
    public static void leaveNow(String levelId) {
        DeathScreenLayoutHandler.leaveNappedRun(levelId);
    }
}
