package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.menu.PrefabSideTabButton;
import games.brennan.dungeontrain.registry.ModCreativeTabs;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds two LEFT-side tab buttons to the creative inventory. Each button is
 * a shortcut: clicking calls vanilla's private {@code selectTab} via
 * {@link CreativeModeInventoryScreenAccessor}, switching the inventory
 * into one of our registered {@link net.minecraft.world.item.CreativeModeTab}s
 * ({@link ModCreativeTabs#PREFAB_VARIANTS} / {@link ModCreativeTabs#PREFAB_LOOT}).
 *
 * <p>Vanilla then owns all rendering (items grid, title, scrollbar, tooltips,
 * scroll wheel) — no custom panel overlay, no Z-order fights, no stale
 * "previous tab" content bleeding through.</p>
 *
 * <p>The buttons are anchored to {@code leftPos - 28}, stacked vertically,
 * one per registered prefab tab.</p>
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeModeInventoryScreenPrefabMixin extends AbstractContainerScreen<CreativeModeInventoryScreen.ItemPickerMenu> {

    private CreativeModeInventoryScreenPrefabMixin() {
        super(null, null, null);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void dungeontrain$addSideTabs(CallbackInfo ci) {
        int x = this.leftPos - PrefabSideTabButton.WIDTH;
        int yTop = this.topPos + 4;
        int yBottom = yTop + PrefabSideTabButton.HEIGHT + 4;

        ItemStack variantIcon = new ItemStack(Items.COMMAND_BLOCK);
        ItemStack lootIcon = new ItemStack(Items.CHEST);

        this.addRenderableWidget(new PrefabSideTabButton(
            x, yTop, ModCreativeTabs.PREFAB_VARIANTS.get(), variantIcon,
            Component.translatable("gui.dungeontrain.prefab_tab.variants")));
        this.addRenderableWidget(new PrefabSideTabButton(
            x, yBottom, ModCreativeTabs.PREFAB_LOOT.get(), lootIcon,
            Component.translatable("gui.dungeontrain.prefab_tab.loot")));
    }
}
