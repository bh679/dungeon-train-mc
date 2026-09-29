package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.menu.PrefabSideTabButton;
import games.brennan.dungeontrain.registry.ModCreativeTabs;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds two LEFT-side tabs to the creative inventory, styled as vanilla
 * creative tabs. Each is a shortcut: clicking calls vanilla's private
 * {@code selectTab} via {@link CreativeModeInventoryScreenAccessor},
 * switching the inventory into one of our registered
 * {@link net.minecraft.world.item.CreativeModeTab}s
 * ({@link ModCreativeTabs#STAGE_BLOCKS} / {@link ModCreativeTabs#PREFAB_LOOT}).
 * Block Variants stays reachable through vanilla's paged tab row.
 *
 * <p>Vanilla then owns all rendering (items grid, title, scrollbar, tooltips,
 * scroll wheel) — no custom panel overlay, no Z-order fights, no stale
 * "previous tab" content bleeding through.</p>
 *
 * <p>The tabs are anchored to {@code leftPos - 28}, stacked vertically at
 * vanilla's 27px tab pitch from the panel's top corner.</p>
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeModeInventoryScreenPrefabMixin extends AbstractContainerScreen<CreativeModeInventoryScreen.ItemPickerMenu> {

    private CreativeModeInventoryScreenPrefabMixin() {
        super(null, null, null);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void dungeontrain$addSideTabs(CallbackInfo ci) {
        int x = this.leftPos - PrefabSideTabButton.WIDTH;
        this.addRenderableWidget(new PrefabSideTabButton(
            x, this.topPos, 0, ModCreativeTabs.STAGE_BLOCKS.get()));
        this.addRenderableWidget(new PrefabSideTabButton(
            x, this.topPos + PrefabSideTabButton.PITCH, 1, ModCreativeTabs.PREFAB_LOOT.get()));
    }
}
