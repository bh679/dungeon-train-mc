package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.CapstoneNeeds;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.advancements.AdvancementTab;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes which tab is being drawn, so {@link AdvancementTabTypeDecorMixin} can halo (or gild) a tab that holds what the
 * pinned capstone still needs ({@link CapstoneNeeds}). Better Advancements twin: {@code BetterAdvancementTabHaloMixin}.
 */
@Mixin(AdvancementTab.class)
public abstract class AdvancementTabHaloMixin {

    @Shadow @Final private AdvancementNode rootNode;

    @Inject(method = "drawTab", at = @At("HEAD"))
    private void dungeontrain$beginTab(GuiGraphics guiGraphics, int offsetX, int offsetY, boolean isSelected, CallbackInfo ci) {
        CapstoneNeeds.drawingTab(this.rootNode.holder().id());
    }

    @Inject(method = "drawTab", at = @At("RETURN"))
    private void dungeontrain$endTab(GuiGraphics guiGraphics, int offsetX, int offsetY, boolean isSelected, CallbackInfo ci) {
        CapstoneNeeds.drawingTab(null);
    }
}
