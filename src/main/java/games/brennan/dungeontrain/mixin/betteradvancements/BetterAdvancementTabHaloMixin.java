package games.brennan.dungeontrain.mixin.betteradvancements;

import games.brennan.dungeontrain.client.CapstoneNeeds;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Better Advancements twin of {@code AdvancementTabHaloMixin}: which tab is being drawn. */
@Mixin(targets = "betteradvancements.common.gui.BetterAdvancementTab", remap = false)
public abstract class BetterAdvancementTabHaloMixin {

    @Shadow @Final private AdvancementNode rootNode;

    @Inject(method = "drawTab", at = @At("HEAD"))
    private void dungeontrain$beginTab(GuiGraphics g, int left, int top, int width, int height, boolean selected, CallbackInfo ci) {
        CapstoneNeeds.drawingTab(this.rootNode.holder().id());
    }

    @Inject(method = "drawTab", at = @At("RETURN"))
    private void dungeontrain$endTab(GuiGraphics g, int left, int top, int width, int height, boolean selected, CallbackInfo ci) {
        CapstoneNeeds.drawingTab(null);
    }
}
