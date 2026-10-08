package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.client.CapstoneNeeds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The "still needed" halo around a tab, like the one on the advancement tiles: the tab's own sprite drawn
 * {@link CapstoneNeeds#HALO_PAD} bigger on every side and tinted aqua, behind the tab.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.advancements.AdvancementTabType") // package-private
public abstract class AdvancementTabTypeHaloMixin {

    @WrapOperation(method = "draw", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lnet/minecraft/resources/ResourceLocation;IIII)V"))
    private void dungeontrain$haloTab(GuiGraphics g, ResourceLocation sprite, int x, int y, int w, int h, Operation<Void> original) {
        if (CapstoneNeeds.currentTabMarked()) {
            int p = CapstoneNeeds.HALO_PAD;
            CapstoneNeeds.inHaloColour(g, () -> original.call(g, sprite, x - p, y - p, w + 2 * p, h + 2 * p));
        }
        original.call(g, sprite, x, y, w, h);
    }
}
