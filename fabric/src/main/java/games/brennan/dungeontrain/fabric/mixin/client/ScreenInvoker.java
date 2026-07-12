package games.brennan.dungeontrain.fabric.mixin.client;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invoker for the protected {@code Screen.addRenderableWidget} — lets the
 * {@code DtScreenInit} carrier add a widget to a screen (the {@code SCREEN_INIT_POST}
 * handlers' Editor/Discord/etc. buttons), the Fabric equivalent of NeoForge's
 * {@code ScreenEvent.Init.Post.addListener}.
 */
@Mixin(Screen.class)
public interface ScreenInvoker {

    @Invoker("addRenderableWidget")
    <T extends GuiEventListener & Renderable & NarratableEntry> T dungeonTrain$addRenderableWidget(T widget);
}
