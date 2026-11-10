package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.logo.LogoRingPlacement;
import games.brennan.dungeontrain.client.logo.LogoRingRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The animated ring logo on the main menu, drawn straight after the panorama so the buttons and
 * the wordmark sit on top of it. Where it goes is {@link LogoRingPlacement#title()}.
 *
 * <p>A mixin because the title screen overrides {@code renderBackground} with nothing, so
 * NeoForge's {@code ScreenEvent.BackgroundRendered} never fires there.</p>
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenLogoRingMixin extends Screen {

    /** Wordmark placement: the ring behind the 274-wide DUNGEON TRAIN wordmark at the top. */
    private static final float WORDMARK_RING = 120f;
    private static final float WORDMARK_CENTRE_Y = 50f;
    /** Background placement: the ring's height as a share of the screen's. */
    private static final float BACKGROUND_SHARE = 0.95f;

    protected TitleScreenLogoRingMixin(Component title) {
        super(title);
    }

    @Inject(
        method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gui/screens/TitleScreen;renderPanorama(Lnet/minecraft/client/gui/GuiGraphics;F)V",
                 shift = At.Shift.AFTER)
    )
    private void dungeontrain$drawLogoRing(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        switch (LogoRingPlacement.title()) {
            case BACKGROUND -> {
                float size = this.height * BACKGROUND_SHARE;
                LogoRingRenderer.get().draw(g, (this.width - size) / 2f, (this.height - size) / 2f, size, 1f, 1f);
            }
            case WORDMARK -> LogoRingRenderer.get().draw(g, (this.width - WORDMARK_RING) / 2f,
                    WORDMARK_CENTRE_Y - WORDMARK_RING / 2f, WORDMARK_RING, 1f, 2f);
            case OFF -> { }
        }
    }
}
