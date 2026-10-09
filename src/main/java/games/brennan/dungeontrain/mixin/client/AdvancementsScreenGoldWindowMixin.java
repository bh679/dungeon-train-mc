package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.client.TabCompletion;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.advancements.AdvancementTab;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The advancements window's border in gold while the selected tab is fully completed ({@link TabCompletion}).
 * Better Advancements twin: {@code BetterAdvancementsScreenGoldWindowMixin}.
 */
@Mixin(AdvancementsScreen.class)
public abstract class AdvancementsScreenGoldWindowMixin {

    @Shadow private AdvancementTab selectedTab;

    @WrapOperation(method = "renderWindow", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIII)V"))
    private void dungeontrain$goldWindow(GuiGraphics g, ResourceLocation tex, int x, int y, int u, int v, int w, int h,
                                         Operation<Void> original) {
        if (selectedTab != null && TabCompletion.isComplete(selectedTab.getRootNode().holder().id())) {
            TabCompletion.inGold(g, () -> original.call(g, tex, x, y, u, v, w, h));
        } else {
            original.call(g, tex, x, y, u, v, w, h);
        }
    }
}
