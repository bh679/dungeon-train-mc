package games.brennan.dungeontrain.mixin.client;

import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The two private {@link BookEditScreen} methods the Live Eye text filter needs to rewrite the page
 * being typed ({@link games.brennan.dungeontrain.client.live.LiveTextCensorClient}): replace the
 * current page's text, then drop the cached layout so the masked word renders at once.
 */
@Mixin(BookEditScreen.class)
public interface BookEditScreenInvoker {

    @Invoker("setCurrentPageText")
    void dungeontrain$setCurrentPageText(String text);

    @Invoker("clearDisplayCache")
    void dungeontrain$clearDisplayCache();
}
