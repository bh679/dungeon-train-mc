package games.brennan.dungeontrain.client.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * Sends a command from the worldspace menu through the normal client chat
 * path. No leading slash — {@code sendUnsignedCommand} / {@code sendCommand}
 * both expect bare command text.
 */
public final class CommandRunner {

    private CommandRunner() {}

    public static void run(String commandNoSlash) {
        ClientPacketListener conn = Minecraft.getInstance().getConnection();
        if (conn == null) return;
        if (!conn.sendUnsignedCommand(commandNoSlash)) {
            conn.sendCommand(commandNoSlash);
        }
    }
}
