package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.block.stage.StageBlocksTabLayout;
import games.brennan.dungeontrain.registry.ModCreativeTabs;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Collection;
import java.util.List;

/**
 * Lays the Stage Blocks tab out in family rows ({@link StageBlocksTabLayout}) by padding the
 * screen's item list with empty stacks. A tab's own contents cannot hold blanks — it rejects empty
 * and repeated stacks — but the list the grid reads from can, and an empty stack there is an
 * ordinary empty slot: no tooltip, nothing to pick up.
 *
 * <p>Both places the screen copies a tab's items into that list are covered: {@code selectTab}
 * (opening the tab) and {@code refreshCurrentTabContents} (the tab rebuilding while it is open).
 * Every other tab's list passes through untouched.</p>
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeStageBlocksLayoutMixin {

    @Shadow
    private static CreativeModeTab selectedTab;

    private CreativeStageBlocksLayoutMixin() {
        // Mixin classes are never instantiated directly.
    }

    @ModifyArg(
        method = {"selectTab", "refreshCurrentTabContents"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/NonNullList;addAll(Ljava/util/Collection;)Z"))
    private Collection<ItemStack> dungeontrain$padStageBlockRows(Collection<ItemStack> items) {
        if (selectedTab != ModCreativeTabs.STAGE_BLOCKS.get()) return items;
        return StageBlocksTabLayout.padded(List.copyOf(items), ItemStack.EMPTY);
    }
}
