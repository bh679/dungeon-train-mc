package games.brennan.dungeontrain.mixin.client;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to {@link ChatScreen}'s input box, so the Live Eye text filter
 * ({@link games.brennan.dungeontrain.client.live.LiveTextCensorClient}) can rewrite a listed word the
 * moment it is typed — before the line is ever on the streamer's screen.
 */
@Mixin(ChatScreen.class)
public interface ChatScreenAccessor {

    @Accessor("input")
    EditBox dungeontrain$getInput();
}
