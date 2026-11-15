package games.brennan.dungeontrain.client.nap;

import games.brennan.dungeontrain.client.DeathScreenLayoutHandler;

/**
 * The client half of taking a nap: the server has saved it ({@code NapSavedPacket}), so the player
 * leaves to the title screen with the world deleted. The nap waits in its slot (Free Play or Survival,
 * and difficulty) until a new run of the same kind starts.
 */
public final class NapQuit {

    private NapQuit() {}

    public static void leave(String levelId) {
        DeathScreenLayoutHandler.leaveNappedRun(levelId);
    }
}
