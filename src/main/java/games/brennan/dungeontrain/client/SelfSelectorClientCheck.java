package games.brennan.dungeontrain.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * Client half of {@link games.brennan.dungeontrain.advancement.SelfSelectorGrant}: does the command
 * tree the server sent us contain an {@code advancement} node? For a non-op that is only true when the
 * server decided we may run {@code /advancement revoke @s everything}, so it doubles as "may I write
 * {@code @s}" for the chat box's syntax colouring. Purely cosmetic — the server re-checks on its own.
 */
public final class SelfSelectorClientCheck {

    private SelfSelectorClientCheck() {}

    public static boolean serverExposedAdvancementCommand() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.getCommands().getRoot().getChild("advancement") != null;
    }
}
