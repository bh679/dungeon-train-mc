package games.brennan.dungeontrain.mixin.betteradvancements;

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
 * <p>Vanilla's screen is unaffected by all of this: it uses a consistent {@code 234 x 113} for both
 * the scissor and the clamp.</p>
 */
@Mixin(targets = "betteradvancements.common.gui.BetterAdvancementTab", remap = false)
public abstract class BetterAdvancementTabScrollMixin {

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
     * Substitute the real viewport width for the screen width the caller passed. Falls through to
     * the original before the first frame has drawn, where stock behaviour applies exactly as it
     * does today.
     */
    @ModifyVariable(method = "scroll", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int dungeontrain$useDrawnViewportWidth(int width) {
        return dungeontrain$viewportWidth > 0 ? dungeontrain$viewportWidth : width;
    }

    /** As {@link #dungeontrain$useDrawnViewportWidth}, for the height — the visible half of the bug. */
    @ModifyVariable(method = "scroll", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int dungeontrain$useDrawnViewportHeight(int height) {
        return dungeontrain$viewportHeight > 0 ? dungeontrain$viewportHeight : height;
    }
}
