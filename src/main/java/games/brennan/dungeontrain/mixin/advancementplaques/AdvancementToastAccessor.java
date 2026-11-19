package games.brennan.dungeontrain.mixin.advancementplaques;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Which advancement an Advancement Plaques plaque is for: it wraps a vanilla toast. */
@Mixin(AdvancementToast.class)
public interface AdvancementToastAccessor {

    @Accessor("advancement")
    AdvancementHolder dungeontrain$advancement();
}
