package games.brennan.dungeontrain.platform.event;

import com.mojang.datafixers.util.Either;
import java.util.List;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

/**
 * Loader-neutral form of NeoForge's {@code RenderTooltipEvent.GatherComponents}
 * (client game bus). Fires while a tooltip's mixed text/component element list is
 * assembled. Not cancellable; the handler MUTATES the live {@code elements} list in
 * place (appends a custom {@link TooltipComponent} via {@code Either.right(...)}).
 * All types are vanilla / DataFixerUpper.
 */
@FunctionalInterface
public interface DtGatherTooltipComponentsCallback {

    void onGatherComponents(ItemStack stack, List<Either<FormattedText, TooltipComponent>> elements);
}
