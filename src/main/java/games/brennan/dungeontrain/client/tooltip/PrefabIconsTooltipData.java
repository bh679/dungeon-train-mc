package games.brennan.dungeontrain.client.tooltip;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Data carrier for the prefab icon-grid tooltip row. Resolved on the client
 * during {@link net.neoforged.neoforge.client.event.RenderTooltipEvent.GatherComponents}
 * from the prefab id stamped on the hovered stack, then mapped to
 * {@link PrefabIconsClientTooltipComponent} by the registered factory.
 *
 * <p>{@code totalCount} is the un-truncated entry count — the renderer uses
 * it to draw "+N more" text when the prefab has more entries than fit in
 * the icon grid (capped at {@link PrefabIconsClientTooltipComponent#MAX_ICONS}).</p>
 */
@OnlyIn(Dist.CLIENT)
public record PrefabIconsTooltipData(List<ItemStack> stacks, int totalCount) implements TooltipComponent {

    public PrefabIconsTooltipData {
        stacks = List.copyOf(stacks);
    }

    public int hiddenCount() {
        return Math.max(0, totalCount - stacks.size());
    }
}
