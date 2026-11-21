package games.brennan.dungeontrain.perks;

import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.OpenPerksMenuPacket;
import net.minecraft.server.level.ServerPlayer;

/** Server side door into the perks menu: asks the player's client to open it. */
public final class PerkMenu {

    private PerkMenu() {}

    public static void openPerksMenu(ServerPlayer player) {
        DungeonTrainNet.sendTo(player, new OpenPerksMenuPacket(PerkPoints.active(player)));
    }
}
