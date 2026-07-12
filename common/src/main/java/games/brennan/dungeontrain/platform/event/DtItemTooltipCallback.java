package games.brennan.dungeontrain.platform.event;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Loader-neutral form of NeoForge's {@code ItemTooltipEvent} (client game bus).
 * Fires while an item's tooltip lines are assembled. Not treated as cancellable by
 * DT; the handler MUTATES the live {@code tooltip} line list in place (adds rows) —
 * the same {@code event.getToolTip().add(...)} side effect. Both parameters are
 * vanilla; a Fabric bridge supplies them from {@code ItemTooltipCallback}.
 */
@FunctionalInterface
public interface DtItemTooltipCallback {

    void onItemTooltip(ItemStack stack, List<Component> tooltip);
}
