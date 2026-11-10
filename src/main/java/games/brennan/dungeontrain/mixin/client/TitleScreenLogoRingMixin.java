package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.LoadingScreenTheme;
import games.brennan.dungeontrain.client.logo.LogoRingRenderer;
import games.brennan.dungeontrain.client.logo.MenuBackdrop;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The main menu's backdrop: in place of the vanilla panorama, a dimmed slideshow of Dungeon Train
 * ride photos ({@link MenuBackdrop}, over the loading screens' dark gradient until they are ready)
 * with the animated ring logo huge across it: half again the screen's longer side, centred low
 * and to the right so the ring sweeps in from the bottom-right corner and runs off the edges. The
 * buttons and wordmark draw on top.
 *
 * <p>Where the ring can't draw (its shader missing, or its cracks still building) the gradient
 * shows alone.</p>
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenLogoRingMixin extends Screen {

    /** The ring's size as a share of the screen's longer side. */
    private static final float RING_SHARE = 1.5f;
    /** The ring's centre, as a share of the screen's width and height (bottom right). */
    private static final float CENTRE_X = 0.8f;
    private static final float CENTRE_Y = 0.85f;
    /** The menu's ring turns at this share of the website's pace: slow, behind the buttons. */
    private static final float RING_SPEED = 0.4f;
    /** Target pixels per screen pixel: the ring is huge, so a little under 1 keeps it cheap. */
    private static final float RING_RENDER_SCALE = 0.75f;

    protected TitleScreenLogoRingMixin(Component title) {
        super(title);
    }

    @Inject(method = "renderPanorama(Lnet/minecraft/client/gui/GuiGraphics;F)V", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$logoBackdrop(GuiGraphics g, float partialTick, CallbackInfo ci) {
        LoadingScreenTheme.fillBackground(g, this.width, this.height);
        MenuBackdrop.get().draw(g, this.width, this.height);
        float size = Math.max(this.width, this.height) * RING_SHARE;
        LogoRingRenderer.get().drawRing(g, this.width * CENTRE_X - size / 2f, this.height * CENTRE_Y - size / 2f, size, 1f, RING_RENDER_SCALE, RING_SPEED);
        ci.cancel();
    }
}
