package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.event.PrefabUseHandler;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tints the slot background for prefab stacks marked
 * {@link PrefabUseHandler#NBT_PREFAB_UNCOMMITTED} — prefabs saved without
 * dev mode that exist only in user config, not yet in the source tree /
 * mod jar.
 *
 * <p>Targets {@code AbstractContainerScreen} (rather than
 * {@code CreativeModeInventoryScreen} where the prefab tabs live) because
 * {@code renderSlot} is declared on the parent class — Mixin only sees
 * methods declared on the target class, not inherited ones, so injecting on
 * the subclass would crash with "could not find any targets matching".</p>
 *
 * <p>The NBT marker is only set on stacks built by
 * {@link games.brennan.dungeontrain.registry.ModCreativeTabs#buildPrefabStack},
 * so non-prefab slots short-circuit on the {@code getBoolean} → false check.
 * Touching every container screen is safe.</p>
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenPrefabTintMixin {

    /**
     * Injected at HEAD so vanilla draws the item icon and decorations on top
     * of our fill. {@code GuiGraphics} has already been translated by
     * {@code (leftPos, topPos)} when {@code renderSlot} runs, so
     * {@code (slot.x, slot.y)} lands on the correct screen pixels.
     */
    @Inject(method = "renderSlot", at = @At("HEAD"))
    private void dungeontrain$tintUncommittedPrefab(GuiGraphics g, Slot slot, CallbackInfo ci) {
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) return;
        CompoundTag tag = stack.getTag();
        if (tag == null) return;
        if (!tag.getBoolean(PrefabUseHandler.NBT_PREFAB_UNCOMMITTED)) return;
        // Translucent yellow — visible behind the item icon without drowning it.
        g.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, 0x80FFC800);
    }
}
