package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.client.HiddenGapConnector;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.advancements.AdvancementWidget;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Draws the "…" across an advancement's incoming connector on vanilla's advancements screen when two or
 * more hidden advancements were skipped there — see {@link HiddenGapConnector}. Better Advancements'
 * screen has its own copy, {@code BetterAdvancementWidgetGapMixin}.
 */
@Mixin(AdvancementWidget.class)
public abstract class AdvancementWidgetGapMixin {

    @Shadow @Final private AdvancementNode advancementNode;

    @Shadow @Final private int x;

    @Shadow @Final private int y;

    @WrapOperation(method = "drawConnectivity",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/client/gui/GuiGraphics;hLine(IIII)V"))
    private void dungeontrain$gapDots(GuiGraphics graphics, int from, int to, int lineY, int colour,
                                      Operation<Void> original,
                                      @Local(argsOnly = true, ordinal = 0) int originX,
                                      @Local(argsOnly = true, ordinal = 1) int originY,
                                      @Local(argsOnly = true) boolean dropShadow) {
        HiddenGapConnector.hLine(graphics, from, to, lineY, colour, advancementNode,
            originX + x + 13, originY + y + 13, dropShadow,
            (a, b, ly, c) -> original.call(graphics, a, b, ly, c));
    }
}
