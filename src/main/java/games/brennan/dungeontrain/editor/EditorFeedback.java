package games.brennan.dungeontrain.editor;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** The editor's one-line answers to a gesture: coloured text on the action bar. */
public final class EditorFeedback {

    private EditorFeedback() {}

    public static void actionBar(ServerPlayer player, Component text, ChatFormatting colour) {
        player.displayClientMessage(text.copy().withStyle(colour), true);
    }
}
