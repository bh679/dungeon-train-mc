package games.brennan.dungeontrain.mixin.betteradvancements;

import games.brennan.dungeontrain.client.AdvancementWindowRow;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the Perks button (and its points count) where Better Advancements drew its window this frame. */
@Mixin(targets = "betteradvancements.common.gui.BetterAdvancementsScreen", remap = false)
public abstract class BetterAdvancementsScreenBoundsMixin {

    @Inject(method = "renderWindow", at = @At("HEAD"))
    private void dungeontrain$reportBounds(GuiGraphics g, int left, int top, int right, int bottom, int maxTabs, int skip,
                                           CallbackInfo ci) {
        AdvancementWindowRow.reportWindow(left, top, right, bottom);
    }
}
