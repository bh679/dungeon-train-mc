package games.brennan.dungeontrain.mixin.betteradvancements;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.client.HoveredAdvancement;
import games.brennan.dungeontrain.compat.AdvancementHintText;
import games.brennan.dungeontrain.compat.AdvancementTileDecor;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

    /** {@link AdvancementHintText#maskedDescriptionRevision()} the cache was split under. */
    @Unique
    private int dungeontrain$hiddenDescRevision;

    @Shadow protected int x;

    @Shadow protected int y;

    /**
     * Draw the tracked halo behind the tile, then fade a tile this life has ruled out. The frame
     * blit and icon render both go through the current shader colour, so a half-alpha colour set
     * here dims both — see {@link AdvancementTileDecor} for why it is restored right after the icon
     * and not at RETURN.
     */
    @Inject(method = "draw", at = @At("HEAD"))
    private void dungeontrain$decorateStart(GuiGraphics guiGraphics, int originX, int originY, CallbackInfo ci) {
        if (advancementNode == null) return;
        AdvancementTileDecor.beforeTile(guiGraphics, advancementNode, advancementProgress, originX, originY, x, y);
    }

    /** Restore the shader colour once the icon is down. */
    @Inject(method = "draw",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/GuiGraphics;renderFakeItem(Lnet/minecraft/world/item/ItemStack;II)V",
                     shift = At.Shift.AFTER))
    private void dungeontrain$decorateAfterIcon(GuiGraphics guiGraphics, int originX, int originY, CallbackInfo ci) {
        if (advancementNode == null) return;
        AdvancementTileDecor.afterIcon(guiGraphics, advancementNode.holder().id(), advancementProgress);
    }

    @Inject(method = "draw", at = @At("RETURN"))
    private void dungeontrain$decorateEnd(GuiGraphics guiGraphics, int originX, int originY, CallbackInfo ci) {
        if (advancementNode == null) return;
        AdvancementTileDecor.afterDraw(guiGraphics, advancementNode.holder().id(), advancementProgress);
    }

    /** The hover tooltip redraws the tile's frame — put the tracked halo behind it again (unfaded). */
    @WrapOperation(method = "drawHover",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lnet/minecraft/resources/ResourceLocation;IIII)V"))
    private void dungeontrain$hoverFrame(GuiGraphics guiGraphics, ResourceLocation sprite, int x, int y, int w, int h,
                                         Operation<Void> original) {
        AdvancementTileDecor.wrapHoverFrame(guiGraphics, sprite, x, y, advancementNode, advancementProgress,
            () -> original.call(guiGraphics, sprite, x, y, w, h));
    }

    /** Remember which tile is under the mouse so a click on BA's screen can toggle tracking on it. */
    @Inject(method = "drawHover", at = @At("HEAD"))
    private void dungeontrain$recordHover(CallbackInfo ci) {
        if (advancementNode != null) {
            HoveredAdvancement.record(advancementNode.holder().id(), advancementProgress);
        }
    }


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
        int revision = AdvancementHintText.maskedDescriptionRevision();
        if (dungeontrain$hiddenDesc == null || dungeontrain$hiddenDescRevision != revision) {
            dungeontrain$hiddenDesc = minecraft.font.split(
                AdvancementHintText.maskedDescription(advancementNode.holder().id()), width);
            dungeontrain$hiddenDescRevision = revision;
        }
        return dungeontrain$hiddenDesc;
    }
}
