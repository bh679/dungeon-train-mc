package games.brennan.dungeontrain.mixin.client;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * Edit access to {@link ChatComponent}'s history, so a chat line can be re-drawn in place — a Discord
 * reaction arriving on it, or a custom emoji image finishing its download
 * ({@link games.brennan.dungeontrain.client.chat.ChatLineEditor}). Vanilla only offers delete.
 */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {

    @Accessor("allMessages")
    List<GuiMessage> dungeontrain$getAllMessages();

    /** The wrapped lines as drawn, newest first: where the chat's top currently is. */
    @Accessor("trimmedMessages")
    List<GuiMessage.Line> dungeontrain$getTrimmedMessages();

    @Invoker("refreshTrimmedMessages")
    void dungeontrain$refreshTrimmedMessages();
}
