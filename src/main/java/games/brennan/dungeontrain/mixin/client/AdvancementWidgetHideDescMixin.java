package games.brennan.dungeontrain.mixin.client;

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
import net.minecraft.client.gui.screens.advancements.AdvancementWidget;
import net.minecraft.network.chat.Component;
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
 * Hides the description text in the {@link AdvancementWidget} hover
 * tooltip for unearned {@code dungeontrain:*} child advancements while
 * keeping their icon and title visible.
 *
 * <p>Mechanism: every {@code drawHover} access of {@code this.description}
 * is wrapped by {@link ModifyExpressionValue}. When the advancement is in
 * the mod's namespace AND is not yet earned, the wrapper substitutes the
 * advancement's own hint ({@code advancements.<namespace>.<path>.hint},
 * pre-wrapped to the widget's render width) — or a shared {@code ???}
 * placeholder when no hint translation exists — for the real description.
 * Earned advancements and non-mod advancements get the unmodified
 * description.</p>
 *
 * <p>Roots are skipped (path ends in {@code /root}) so the tab opens
 * with a visible explanation of what the tab is.</p>
 */
@Mixin(AdvancementWidget.class)
public abstract class AdvancementWidgetHideDescMixin {

    @Shadow @Final private AdvancementNode advancementNode;

    @Shadow private AdvancementProgress progress;

    @Shadow @Final private Minecraft minecraft;

    @Shadow @Final private int width;

    /**
     * Lazily-cached hint/placeholder description, computed on first hide so we
     * don't re-split a Component every frame. Each widget instance maps to a
     * single advancement, so this per-instance cache holds that advancement's
     * resolved hint (or the {@code ???} fallback). Width-bound to {@link #width}
     * (same as the real description) so layout calculations stay consistent.
     */
    @Unique
    private List<FormattedCharSequence> dungeontrain$hiddenDesc;

    /** {@link AdvancementHintText#maskedDescriptionRevision()} the cache was split under. */
    @Unique
    private int dungeontrain$hiddenDescRevision;

    @Shadow private int x;

    @Shadow private int y;

    /**
     * Draw the tracked halo behind the tile, then fade a tile this life has ruled out. The frame
     * blit and icon render both go through the current shader colour, so a half-alpha colour set
     * here dims both — see {@link AdvancementTileDecor} for why it is restored right after the icon
     * and not at RETURN.
     */
    @Inject(method = "draw", at = @At("HEAD"))
    private void dungeontrain$decorateStart(GuiGraphics guiGraphics, int originX, int originY, CallbackInfo ci) {
        if (advancementNode == null) return;
        AdvancementTileDecor.beforeTile(guiGraphics, advancementNode, progress, originX, originY, x, y);
    }

    /** Restore the shader colour once the icon is down. */
    @Inject(method = "draw",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/GuiGraphics;renderFakeItem(Lnet/minecraft/world/item/ItemStack;II)V",
                     shift = At.Shift.AFTER))
    private void dungeontrain$decorateAfterIcon(GuiGraphics guiGraphics, int originX, int originY, CallbackInfo ci) {
        if (advancementNode == null) return;
        AdvancementTileDecor.afterIcon(guiGraphics, advancementNode.holder().id(), progress);
    }

    @Inject(method = "draw", at = @At("RETURN"))
    private void dungeontrain$decorateEnd(GuiGraphics guiGraphics, int originX, int originY, CallbackInfo ci) {
        if (advancementNode == null) return;
        AdvancementTileDecor.afterDraw(guiGraphics, advancementNode.holder().id(), progress);
    }

    /** The hover tooltip redraws the tile's frame — put the tracked halo behind it again (unfaded). */
    @WrapOperation(method = "drawHover",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lnet/minecraft/resources/ResourceLocation;IIII)V"))
    private void dungeontrain$hoverFrame(GuiGraphics guiGraphics, ResourceLocation sprite, int x, int y, int w, int h,
                                         Operation<Void> original) {
        AdvancementTileDecor.wrapHoverFrame(guiGraphics, sprite, x, y, advancementNode, progress,
            () -> original.call(guiGraphics, sprite, x, y, w, h));
    }

    /** Whether {@code drawConnectivity} pushed a scissor for this tile's incoming connector. */
    @Unique
    private boolean dungeontrain$connectorClipped;

    /** Stop the connector at a faded tile's frame — see {@link AdvancementTileDecor#beginConnectorClip}. */
    @Inject(method = "drawConnectivity", at = @At("HEAD"))
    private void dungeontrain$clipConnectorStart(GuiGraphics guiGraphics, int originX, int originY, boolean dropShadow, CallbackInfo ci) {
        dungeontrain$connectorClipped = AdvancementTileDecor.beginConnectorClip(guiGraphics, advancementNode, progress, originX, x);
    }

    /**
     * Pop the clip before the first recursion into a child's {@code drawConnectivity} (the
     * children's own lines must not be clipped), and at RETURN as the fallback for a widget with
     * no children. The flag makes the pop happen exactly once.
     */
    @Inject(method = "drawConnectivity",
            at = {@At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/advancements/AdvancementWidget;drawConnectivity(Lnet/minecraft/client/gui/GuiGraphics;IIZ)V"), @At("RETURN")})
    private void dungeontrain$clipConnectorEnd(GuiGraphics guiGraphics, int originX, int originY, boolean dropShadow, CallbackInfo ci) {
        if (dungeontrain$connectorClipped) {
            dungeontrain$connectorClipped = false;
            AdvancementTileDecor.endConnectorClip(guiGraphics);
        }
    }

    /** Remember which tile is under the mouse so a click on the screen can toggle tracking on it. */
    @Inject(method = "drawHover", at = @At("HEAD"))
    private void dungeontrain$recordHover(CallbackInfo ci) {
        if (advancementNode != null) {
            HoveredAdvancement.record(advancementNode.holder().id(), progress);
        }
    }


    @ModifyExpressionValue(
        method = "drawHover",
        at = @At(value = "FIELD",
                 target = "Lnet/minecraft/client/gui/screens/advancements/AdvancementWidget;description:Ljava/util/List;")
    )
    private List<FormattedCharSequence> dungeontrain$swapDescription(List<FormattedCharSequence> original) {
        if (!dungeontrain$shouldHideDescription()) return original;
        return dungeontrain$getHiddenDesc();
    }

    /**
     * Treat a {@code hidden:true} {@code dungeontrain:*} advancement as visible
     * for tile rendering ({@code draw}) and hover hit-testing
     * ({@code isMouseOver}) once it is on screen at all. Vanilla gates both on
     * {@code isHidden()}: a hidden advancement draws no icon and can't be
     * hovered until earned. But DT hides most of its tree only to reveal a
     * node's children when that node is earned (server-side frontier gate), so
     * any DT widget that exists client-side has already been cleared for
     * display. Forcing the guard's {@code isHidden()} to {@code false} for mod
     * advancements lets the frontier draw with its real icon and show its hover
     * (its description stays masked to a hint via the swap above). Non-mod
     * advancements are untouched.
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

    @Unique
    private boolean dungeontrain$shouldHideDescription() {
        if (advancementNode == null) return false;
        return AdvancementHintText.shouldMask(advancementNode.holder().id(), progress);
    }

    @Unique
    private List<FormattedCharSequence> dungeontrain$getHiddenDesc() {
        int revision = AdvancementHintText.maskedDescriptionRevision();
        if (dungeontrain$hiddenDesc == null || dungeontrain$hiddenDescRevision != revision) {
            dungeontrain$hiddenDesc = minecraft.font.split(dungeontrain$maskedDescription(), width);
            dungeontrain$hiddenDescRevision = revision;
        }
        return dungeontrain$hiddenDesc;
    }

    /**
     * The text shown in place of the hidden description — the hint plus any "not this life" and
     * tracking lines; see {@link AdvancementHintText#maskedDescription(ResourceLocation)}. Callers
     * only reach this once {@link #dungeontrain$shouldHideDescription()} has confirmed a non-null
     * node, so {@code advancementNode} is safe to dereference.
     */
    @Unique
    private Component dungeontrain$maskedDescription() {
        return AdvancementHintText.maskedDescription(advancementNode.holder().id());
    }
}
