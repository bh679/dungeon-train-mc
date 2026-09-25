package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.menu.MenuClickModifiers;
import games.brennan.dungeontrain.client.menu.PrefabTabState;
import games.brennan.dungeontrain.editor.PrefabDeletes;
import games.brennan.dungeontrain.net.DeletePrefabPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.registry.ModCreativeTabs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Cmd-click (Ctrl off macOS) a saved prefab in one of the creative menu's prefab tabs to be asked
 * whether to delete it.
 *
 * <p>Only prefabs the server synced as deletable for this install (yours, or anything for the dev
 * — see {@link PrefabDeletes}) raise the prompt; every other Cmd-click is left to vanilla. The
 * server re-checks on {@link DeletePrefabPacket}, then re-syncs the tabs so the slot disappears.
 * Same {@code slotClicked} seam as {@link CreativeShiftToHotbarMixin}.</p>
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativePrefabDeleteMixin extends AbstractContainerScreen<CreativeModeInventoryScreen.ItemPickerMenu> {

    private CreativePrefabDeleteMixin() {
        super(null, null, null);
    }

    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$cmdClickDeletePrefab(Slot slot, int slotId, int mouseButton, ClickType clickType, CallbackInfo ci) {
        if (mouseButton != 0 || slot == null || !MenuClickModifiers.cmdDown()) return;
        if (!dungeontrain$isPrefabTab(CreativeModeInventoryScreenAccessor.dungeontrain$getSelectedTab())) return;
        if (!((CreativeModeInventoryScreenAccessor) this).dungeontrain$isCreativeSlot(slot)) return;

        Optional<PrefabTabState.PrefabRef> ref = PrefabTabState.refOf(slot.getItem());
        if (ref.isEmpty() || !PrefabTabState.deletableByViewer(ref.get().kind(), ref.get().id())) return;

        ci.cancel();
        dungeontrain$confirmDelete(ref.get());
    }

    private void dungeontrain$confirmDelete(PrefabTabState.PrefabRef ref) {
        Minecraft minecraft = Minecraft.getInstance();
        CreativeModeInventoryScreen creative = (CreativeModeInventoryScreen) (Object) this;
        String titleKey = ref.kind() == PrefabDeletes.Kind.LOOT
            ? "gui.dungeontrain.prefab_delete.title.loot"
            : "gui.dungeontrain.prefab_delete.title.variant";
        minecraft.setScreen(new ConfirmScreen(
            confirmed -> {
                if (confirmed) DungeonTrainNet.sendToServer(new DeletePrefabPacket(ref.kind(), ref.id()));
                minecraft.setScreen(creative);
            },
            Component.translatable(titleKey, ref.id()),
            Component.translatable("gui.dungeontrain.prefab_delete.body")));
    }

    private static boolean dungeontrain$isPrefabTab(CreativeModeTab tab) {
        return tab != null && (tab == ModCreativeTabs.PREFAB_VARIANTS.get()
            || tab == ModCreativeTabs.PREFAB_LOOT.get()
            || tab == ModCreativeTabs.PREFAB_LOOT_ENTITY.get());
    }
}
