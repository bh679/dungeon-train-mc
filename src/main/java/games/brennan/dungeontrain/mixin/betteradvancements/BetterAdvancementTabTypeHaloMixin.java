package games.brennan.dungeontrain.mixin.betteradvancements;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.client.CapstoneNeeds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Better Advancements twin of {@code AdvancementTabTypeHaloMixin}. BA 0.4.3 (1.21.1) blits its tab from a
 * 256×256 sheet ({@code blit(texture, x, y, u, v, w, h)}); the halo stretches that same region bigger behind it.
 */
@Mixin(targets = "betteradvancements.common.gui.BetterAdvancementTabType", remap = false)
public abstract class BetterAdvancementTabTypeHaloMixin {

    private static final int SHEET = 256;

    @WrapOperation(method = "draw", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIII)V"))
    private void dungeontrain$haloTab(GuiGraphics g, ResourceLocation tex, int x, int y, int u, int v, int w, int h,
                                      Operation<Void> original) {
        if (CapstoneNeeds.currentTabMarked()) {
            int p = CapstoneNeeds.HALO_PAD;
            CapstoneNeeds.inHaloColour(g, () -> g.blit(tex, x - p, y - p, w + 2 * p, h + 2 * p, u, v, w, h, SHEET, SHEET));
        }
        original.call(g, tex, x, y, u, v, w, h);
    }
}
