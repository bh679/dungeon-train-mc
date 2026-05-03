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
 * main menu with the Dungeon Train wordmark on top and the Minecraft wordmark
 * as a subtitle below.
 *
 * Sources (mod overrides at the vanilla paths):
 *   assets/minecraft/textures/gui/title/minecraft.png  (1440x173 — Dungeon Train)
 *   assets/minecraft/textures/gui/title/edition.png    (1295x223 — Minecraft wordmark)
 *
 * Why a mixin: vanilla {@link LogoRenderer#renderLogo(GuiGraphics, int, float, int)}
 * blits the main wordmark as two stacked 155x44 tiles from a 256x64 atlas, which
 * mangles single-row artwork. We HEAD-inject + cancel and issue clean full-image
 * blits at the natural aspect ratios.
 */
@Mixin(LogoRenderer.class)
public abstract class MainMenuLogoMixin {

    private static final int DT_SRC_W = 1440;
    private static final int DT_SRC_H = 173;
    private static final int DT_DEST_W = LogoRenderer.LOGO_WIDTH; // 274 — match vanilla on-screen width
    private static final int DT_DEST_H = Math.round((float) DT_DEST_W * DT_SRC_H / DT_SRC_W);

    private static final int MC_SRC_W = 1295;
    private static final int MC_SRC_H = 223;
    private static final int MC_DEST_W = 152; // matches vanilla EDITION on-screen width
    private static final int MC_DEST_H = Math.round((float) MC_DEST_W * MC_SRC_H / MC_SRC_W);

    private static final int SUBTITLE_GAP = 4;

    @Inject(
        method = "renderLogo(Lnet/minecraft/client/gui/GuiGraphics;IFI)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void dungeontrain$renderCustomLogo(GuiGraphics g, int screenWidth, float alpha, int yOffset, CallbackInfo ci) {
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);

        int dtX = (screenWidth - DT_DEST_W) / 2;
        g.blit(LogoRenderer.MINECRAFT_LOGO, dtX, yOffset,
                DT_DEST_W, DT_DEST_H,
                0.0F, 0.0F,
                DT_SRC_W, DT_SRC_H,
                DT_SRC_W, DT_SRC_H);

        int mcX = (screenWidth - MC_DEST_W) / 2;
        int mcY = yOffset + DT_DEST_H + SUBTITLE_GAP;
        g.blit(LogoRenderer.MINECRAFT_EDITION, mcX, mcY,
                MC_DEST_W, MC_DEST_H,
                0.0F, 0.0F,
                MC_SRC_W, MC_SRC_H,
                MC_SRC_W, MC_SRC_H);

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        ci.cancel();
    }
}
