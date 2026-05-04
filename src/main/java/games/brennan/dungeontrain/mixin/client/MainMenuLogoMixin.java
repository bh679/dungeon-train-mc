package games.brennan.dungeontrain.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.LogoRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the vanilla "MINECRAFT" wordmark + "Java Edition" subtitle on the
 * main menu with the single-row Dungeon Train wordmark, and suppresses the
 * edition subtitle entirely.
 *
 * Source (mod override at the vanilla path):
 *   assets/minecraft/textures/gui/title/minecraft.png  (1440x173 — Dungeon Train)
 *
 * Why a mixin: vanilla {@link LogoRenderer#renderLogo(GuiGraphics, int, float, int)}
 * blits the main wordmark as two stacked 155x44 tiles from a 256x64 atlas, which
 * mangles single-row artwork. We HEAD-inject + cancel and issue one clean
 * full-image blit at the natural aspect ratio. Cancelling also drops vanilla's
 * edition.png blit, which is the desired behaviour here.
 */
@Mixin(LogoRenderer.class)
public abstract class MainMenuLogoMixin {

    private static final int DT_SRC_W = 776;
    private static final int DT_SRC_H = 214;
    private static final int DT_DEST_W = LogoRenderer.LOGO_WIDTH; // 274
    private static final int DT_DEST_H = Math.round((float) DT_DEST_W * DT_SRC_H / DT_SRC_W);

    private static final int DT_Y_SHIFT = -10;

    @Inject(
        method = "renderLogo(Lnet/minecraft/client/gui/GuiGraphics;IFI)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void dungeontrain$renderCustomLogo(GuiGraphics g, int screenWidth, float alpha, int yOffset, CallbackInfo ci) {
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);

        int dtX = (screenWidth - DT_DEST_W) / 2;
        int dtY = yOffset + DT_Y_SHIFT;
        g.blit(LogoRenderer.MINECRAFT_LOGO, dtX, dtY,
                DT_DEST_W, DT_DEST_H,
                0.0F, 0.0F,
                DT_SRC_W, DT_SRC_H,
                DT_SRC_W, DT_SRC_H);

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        ci.cancel();
    }
}
