package games.brennan.dungeontrain.mixin.betteradvancements;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.client.HiddenGapConnector;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Better Advancements' copy of {@code AdvancementWidgetGapMixin}: the "…" across a connector that skips
 * two or more hidden advancements. BA draws each connector in {@code drawConnection(graphics, parent, x, y,
 * dropShadow)} on the child widget, as the same {@code hLine}/{@code vLine} elbow as vanilla (its elbow
 * 2px further right) unless its "direct lines" option is on, which draws a slanted rect and gets no dots.
 */
@Mixin(targets = "betteradvancements.common.gui.BetterAdvancementWidget", remap = false)
public abstract class BetterAdvancementWidgetGapMixin {

    @Shadow @Final private AdvancementNode advancementNode;

    @Shadow protected int x;

    @Shadow protected int y;

    @WrapOperation(method = "drawConnection",
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
