package games.brennan.dungeontrain.client.nap;

import games.brennan.dungeontrain.client.DeathScreenLayoutHandler;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * The client half of taking a nap: the server has saved it ({@code NapSavedPacket}), so the next run
 * starts on this run's difficulty, and the player leaves to the title screen with the world deleted.
 */
public final class NapQuit {

    private NapQuit() {}

    public static void leave(String levelId) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null && !server.getWorldData().isHardcore()) {
            ClientDisplayConfig.setNewRunDifficulty(server.getWorldData().getDifficulty());
        }
        DeathScreenLayoutHandler.leaveNappedRun(levelId);
    }
}
