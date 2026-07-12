package games.brennan.dungeontrain.client.tooltip;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Data carrier for the prefab icon-grid tooltip row. Resolved on the client
 * during the tooltip-component gather pass (NeoForge {@code RenderTooltipEvent.GatherComponents},
 * bridged via {@code DtEvents.GATHER_TOOLTIP_COMPONENTS}) from the prefab id stamped
 * on the hovered stack, then mapped to
 * {@link PrefabIconsClientTooltipComponent} by the registered factory.
 *
 * <p>{@code totalCount} is the un-truncated entry count — the renderer uses
 * it to draw "+N more" text when the prefab has more entries than fit in
 * the icon grid (capped at {@link PrefabIconsClientTooltipComponent#MAX_ICONS}).</p>
 */
public record PrefabIconsTooltipData(List<ItemStack> stacks, int totalCount) implements TooltipComponent {

    public PrefabIconsTooltipData {
        stacks = List.copyOf(stacks);
    }

    public int hiddenCount() {
        return Math.max(0, totalCount - stacks.size());
    }
}
