package games.brennan.dungeontrain.mixin.betteradvancements;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets Better Advancements' screen scroll all the way to the edge of a tall advancement tree.
 *
 * <p>This is a bug in BA itself, not in Dungeon Train's compat layer — it affects any tab taller
 * than the window, in any pack. DT's {@code dungeon_train} tree simply makes it obvious, because the
 * frontier reveal grows it well past one screen height.</p>
 *
 * <p><b>The bug.</b> {@code BetterAdvancementTab.scroll} clamps the offset against the viewport size
 * it is handed:</p>
 *
 * <pre>{@code
 * if (maxY - minY > height)
 *     scrollY = round(clamp(scrollY + dragY, -(maxY - height), -minY));
 * }</pre>
 *
 * <p>Reaching the bottom of the content needs {@code scrollY == -(maxY - viewportHeight)}, so an
 * overstated {@code height} costs exactly that much travel. Both call sites overstate it:
 * {@code BetterAdvancementsScreen.mouseScrolled} passes the screen's own {@code width}/{@code
 * height}, and {@code mouseDragged} passes {@code internalWidth}/{@code internalHeight}, which are
 * {@code width * uiScaling / 100} — the same thing at the default {@code uiScaling = 100}.</p>
 *
 * <p>The tree is not drawn into either. {@code BetterAdvancementsScreen.renderInside} insets the
 * window first — {@code left + 9}, {@code top + 18} (title-bar chrome), {@code right - 9},
 * {@code bottom - 9} — and hands the resulting size to {@code drawContents}, which scissors to
 * exactly that rectangle. So the clamp is out by the window's margin from the screen edge plus 27px
 * of chrome vertically (18px horizontally), and the bottom of the tree stays out of reach. The same
 * mismatch also disables scrolling outright for a tree taller than the true viewport but shorter
 * than the screen.</p>
 *
 * <p><b>The fix.</b> Record the size {@code drawContents} actually scissors to — BA recomputes it
 * every frame — and clamp against that instead of the screen. Reading BA's own number rather than
 * re-deriving its window insets keeps this correct if BA changes its chrome.</p>
 *
 * <p>On top of that, one advancement box of deliberate overscroll past the far edge, so the last
 * row can be pulled clear of the frame rather than sitting flush against it. Note this also relaxes
 * BA's {@code maxY - minY > height} guard by the same amount: a tree that ends within one box of
 * filling the viewport becomes scrollable where it previously was not, which is the intended
 * behaviour rather than a side effect to avoid.</p>
 *
 * <p>Vanilla's screen is unaffected by all of this: it uses a consistent {@code 234 x 113} for both
 * the scissor and the clamp.</p>
 */
@Mixin(targets = "betteradvancements.common.gui.BetterAdvancementTab", remap = false)
public abstract class BetterAdvancementTabScrollMixin {

    /**
     * Overscroll allowance past the far edge, in pixels: one column step
     * ({@code display.getX() * 32}) horizontally, one row step ({@code * 27}) vertically — i.e. one
     * advancement box and its gutter, so the last row can be pulled clear of the frame instead of
     * sitting jammed against it.
     *
     * <p>Applied by *shrinking* the viewport the clamp sees. {@code scroll} bounds the offset at
     * {@code -(maxY - height)}, so a smaller {@code height} moves that bound further negative by
     * exactly the same amount. Only the far edge gains slack — the near-edge bound is
     * {@code -minY}, which does not depend on the viewport at all.</p>
     */
    @Unique
    private static final int DUNGEONTRAIN_OVERSCROLL_X = 32;

    @Unique
    private static final int DUNGEONTRAIN_OVERSCROLL_Y = 27;

    /**
     * Overscroll allowance past the <em>near</em> (top) edge — half a row, so the first row lifts
     * clear of the frame without the tree floating as far as it can drop at the bottom.
     *
     * <p>The viewport trick above cannot reach this edge: {@code scroll} bounds that side at
     * {@code -minY}, which has no viewport term. Shifting the {@code minY} the clamp reads instead
     * moves the bound to {@code -minY + pad}.</p>
     */
    @Unique
    private static final int DUNGEONTRAIN_OVERSCROLL_TOP = 13;

    /** Width of the rectangle {@code drawContents} last scissored to; 0 until the tab has drawn. */
    @Unique
    private int dungeontrain$viewportWidth;

    /** Height of the rectangle {@code drawContents} last scissored to; 0 until the tab has drawn. */
    @Unique
    private int dungeontrain$viewportHeight;

    @Inject(method = "drawContents", at = @At("HEAD"))
    private void dungeontrain$captureViewport(GuiGraphics guiGraphics, int x, int y, int width, int height, CallbackInfo ci) {
        dungeontrain$viewportWidth = width;
        dungeontrain$viewportHeight = height;
    }

    /**
     * Substitute the real viewport width for the screen width the caller passed, less the
     * overscroll allowance. Falls back to the caller's value before the first frame has drawn.
     */
    @ModifyVariable(method = "scroll", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int dungeontrain$useDrawnViewportWidth(int width) {
        int viewport = dungeontrain$viewportWidth > 0 ? dungeontrain$viewportWidth : width;
        return Math.max(1, viewport - DUNGEONTRAIN_OVERSCROLL_X);
    }

    /** As {@link #dungeontrain$useDrawnViewportWidth}, for the height — the visible half of the bug. */
    @ModifyVariable(method = "scroll", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int dungeontrain$useDrawnViewportHeight(int height) {
        int viewport = dungeontrain$viewportHeight > 0 ? dungeontrain$viewportHeight : height;
        return Math.max(1, viewport - DUNGEONTRAIN_OVERSCROLL_Y);
    }

    /**
     * Lift the top bound by {@link #DUNGEONTRAIN_OVERSCROLL_TOP}. {@code scroll} reads {@code minY}
     * twice — once for its {@code maxY - minY > height} guard, once as the clamp's upper bound —
     * and both reads want the shifted value: the bound is what grants the slack, and the guard
     * should agree that the content is that much taller than it measures.
     *
     * <p>{@code minX} is deliberately left alone; the left edge stops where it always has.</p>
     */
    @ModifyExpressionValue(
        method = "scroll",
        at = @At(value = "FIELD",
                 target = "Lbetteradvancements/common/gui/BetterAdvancementTab;minY:I")
    )
    private int dungeontrain$padTopEdge(int minY) {
        return minY - DUNGEONTRAIN_OVERSCROLL_TOP;
    }
}
