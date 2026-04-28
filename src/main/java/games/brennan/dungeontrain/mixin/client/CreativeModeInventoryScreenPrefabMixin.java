package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.menu.PrefabSideTabButton;
import games.brennan.dungeontrain.client.menu.PrefabTabPanel;
import games.brennan.dungeontrain.client.menu.PrefabTabState;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds two LEFT-side tab buttons to the creative inventory and overpaints
 * the items grid with a custom prefab panel when one of those tabs is
 * active.
 *
 * <p>Vanilla creative tabs live on top/bottom rows. Forge does not natively
 * support left-side tabs, so we render our buttons as standalone widgets
 * anchored to the inventory's {@code leftPos}. When a side tab is active
 * the panel ({@link PrefabTabPanel}) overpaints vanilla's items grid; when
 * inactive the inventory looks and behaves exactly as vanilla.</p>
 *
 * <p>Inject points:
 * <ul>
 *   <li>{@code init() TAIL} — spawn the two side tab buttons.</li>
 *   <li>{@code render() TAIL} — paint the active panel + tooltip on top of
 *       vanilla's items grid.</li>
 *   <li>{@code mouseClicked() HEAD cancellable} — consume clicks inside the
 *       active panel before vanilla dispatches them as slot clicks.</li>
 *   <li>{@code mouseScrolled() HEAD cancellable} — consume scroll inside the
 *       active panel.</li>
 *   <li>{@code selectTab() HEAD} — auto-deactivate the side panel when the
 *       user picks a vanilla tab so the panel doesn't blanket the new
 *       items grid.</li>
 * </ul></p>
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeModeInventoryScreenPrefabMixin extends AbstractContainerScreen<CreativeModeInventoryScreen.ItemPickerMenu> {

    // Constructor required by mixin compiler — never called at runtime.
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
            x, yTop, PrefabTabState.Tab.VARIANTS, variantIcon,
            Component.translatable("gui.dungeontrain.prefab_tab.variants")));
        this.addRenderableWidget(new PrefabSideTabButton(
            x, yBottom, PrefabTabState.Tab.LOOT, lootIcon,
            Component.translatable("gui.dungeontrain.prefab_tab.loot")));
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void dungeontrain$renderPanel(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (PrefabTabState.activeTab() == PrefabTabState.Tab.NONE) return;
        PrefabTabPanel.render(g, this.font, this.leftPos, this.topPos, mouseX, mouseY);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$mouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (PrefabTabState.activeTab() == PrefabTabState.Tab.NONE) return;
        if (PrefabTabPanel.handleClick(this.leftPos, this.topPos, mouseX, mouseY, button)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$mouseScrolled(double mouseX, double mouseY, double delta, CallbackInfoReturnable<Boolean> cir) {
        if (PrefabTabState.activeTab() == PrefabTabState.Tab.NONE) return;
        if (PrefabTabPanel.handleScroll(this.leftPos, this.topPos, mouseX, mouseY, delta)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "selectTab", at = @At("HEAD"))
    private void dungeontrain$onSelectTab(net.minecraft.world.item.CreativeModeTab tab, CallbackInfo ci) {
        // User clicked a vanilla top/bottom tab — close our side panel so
        // they see the newly-selected vanilla items.
        PrefabTabState.deactivate();
    }
}
