package games.brennan.dungeontrain.mixin.betteradvancements;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import games.brennan.dungeontrain.compat.AdvancementHintText;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Ports Dungeon Train's advancement fog-of-war onto Better Advancements' replacement widget.
 *
 * <p>DT's presentation is three mixins: a server-side frontier reveal
 * ({@code AdvancementVisibilityEvaluatorMixin}) that syncs a {@code hidden:true} advancement once
 * its parent is earned, a client-side re-layout ({@code AdvancementsCompactLayoutMixin}) on
 * {@code ClientAdvancements}, and
 * {@link games.brennan.dungeontrain.mixin.client.AdvancementWidgetHideDescMixin} on vanilla's
 * {@code AdvancementWidget}, which draws those revealed nodes and masks their descriptions.</p>
 *
 * <p>Better Advancements ships no mixins of its own — it swaps the entire screen for
 * {@code BetterAdvancementsScreen} / {@code BetterAdvancementTab} / {@code BetterAdvancementWidget}
 * via its {@code GuiOpenHandler}. The first two DT mixins are unaffected (they target the server
 * evaluator and {@code ClientAdvancements}), but the third targets a vanilla class BA no longer
 * uses. The result is BA re-applying the vanilla {@code isHidden() && !done} gate — no frame, no
 * icon — while its {@code drawConnectivity}, which has no such gate, still draws every connector.
 * The DT tab renders as a skeleton of lines around a lone root.</p>
 *
 * <p>BA's widget mirrors vanilla's field-for-field, so the two injections port across unchanged.
 * Only vanilla types are referenced (BA's own {@code betterDisplayInfo} / {@code criterionGrid} are
 * left alone), which is what lets this compile with BA absent from the classpath — it is targeted by
 * name and gated by {@link games.brennan.dungeontrain.mixin.BetterAdvancementsMixinPlugin}.</p>
 *
 * <p>Note {@code advancementProgress}: BA's name for the field vanilla calls {@code progress}.</p>
 *
 * @see games.brennan.dungeontrain.compat.AdvancementHintText the masking rules, shared with the
 *      vanilla-screen mixin so the two can't drift apart
 */
@Mixin(targets = "betteradvancements.common.gui.BetterAdvancementWidget", remap = false)
public abstract class BetterAdvancementWidgetCompatMixin {

    @Shadow @Final private AdvancementNode advancementNode;

    @Shadow private AdvancementProgress advancementProgress;

    @Shadow @Final private Minecraft minecraft;

    @Shadow private int width;

    /**
     * Lazily-cached hint/placeholder description, computed on first hide so we don't re-split a
     * Component every frame. Each widget instance maps to a single advancement. Width-bound to
     * {@link #width} (same as the real description) so BA's layout maths stay consistent.
     */
    @Unique
    private List<FormattedCharSequence> dungeontrain$hiddenDesc;

    /**
     * Draw and hover-test a revealed-but-unearned {@code dungeontrain:*} advancement as if it were
     * visible. Any DT widget that exists client-side has already been cleared for display by the
     * server-side frontier gate, so BA's {@code isHidden()} check is the only thing suppressing it.
     * Non-mod advancements keep BA's stock behaviour.
     */
    @ModifyExpressionValue(
        method = {"draw", "isMouseOver"},
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/advancements/DisplayInfo;isHidden()Z")
    )
    private boolean dungeontrain$revealRevealedIcon(boolean original) {
        if (!original) return false;
        if (advancementNode == null) return original;
        return AdvancementHintText.isModAdvancement(advancementNode.holder().id()) ? false : original;
    }

    /**
     * Swap the tooltip description for the advancement's hint while it is unearned. BA reads
     * {@code description} several times across {@code drawHover} (tooltip sizing as well as the draw
     * itself); one FIELD injector covers every read, so the measured and rendered text agree.
     */
    @ModifyExpressionValue(
        method = "drawHover",
        at = @At(value = "FIELD",
                 target = "Lbetteradvancements/common/gui/BetterAdvancementWidget;description:Ljava/util/List;")
    )
    private List<FormattedCharSequence> dungeontrain$swapDescription(List<FormattedCharSequence> original) {
        if (advancementNode == null) return original;
        if (!AdvancementHintText.shouldMask(advancementNode.holder().id(), advancementProgress)) {
            return original;
        }
        if (dungeontrain$hiddenDesc == null) {
            dungeontrain$hiddenDesc = minecraft.font.split(
                AdvancementHintText.hintOrPlaceholder(advancementNode.holder().id()), width);
        }
        return dungeontrain$hiddenDesc;
    }
}
