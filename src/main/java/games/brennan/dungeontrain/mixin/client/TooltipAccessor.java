package games.brennan.dungeontrain.mixin.client;

import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only access to a widget {@link Tooltip}'s message, which vanilla keeps private and only hands
 * out pre-split into lines. The translation editor's screen recorder
 * ({@code ButtonScreenLayouts}) needs the Component itself to see which lang key a tooltip shows.
 */
@Mixin(Tooltip.class)
public interface TooltipAccessor {

    @Accessor("message")
    Component dungeontrain$getMessage();
}
