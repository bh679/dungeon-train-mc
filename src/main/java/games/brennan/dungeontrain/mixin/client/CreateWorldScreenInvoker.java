package games.brennan.dungeontrain.mixin.client;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets {@link CreateWorldScreenCustomContentMixin} re-enter vanilla's private create-and-enter
 * method once the custom-content question has been answered.
 */
@Mixin(CreateWorldScreen.class)
public interface CreateWorldScreenInvoker {

    @Invoker("onCreate")
    void dungeontrain$onCreate();
}
