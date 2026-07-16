package games.brennan.dungeontrain.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import games.brennan.dungeontrain.client.DungeonTrainLanguages;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Badges each language row in the vanilla language-selection list with the Dungeon Train logo
 * when the mod ships a translation for that language (see {@link DungeonTrainLanguages}), so
 * players can see which languages Dungeon Train is localized into.
 *
 * <p>Targets the inner {@code LanguageSelectScreen.LanguageSelectionList.Entry} and draws a small
 * icon at the left edge of the row after the vanilla name render (TAIL). Non-translated languages
 * are untouched.</p>
 */
@Mixin(targets = "net.minecraft.client.gui.screens.options.LanguageSelectScreen$LanguageSelectionList$Entry")
public abstract class LanguageSelectEntryLogoMixin {

    @Shadow @Final String code;

    private static final ResourceLocation DT_LANG_LOGO =
        ResourceLocation.fromNamespaceAndPath("dungeontrain", "textures/gui/language_logo.png");
    /** Source texture is square 64x64. */
    private static final int TEX = 64;

    @Inject(
        method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIIIIIZF)V",
        at = @At("TAIL")
    )
    private void dungeontrain$badgeTranslated(GuiGraphics g, int index, int top, int left, int width,
                                              int height, int mouseX, int mouseY, boolean hovering,
                                              float partialTick, CallbackInfo ci) {
        if (!DungeonTrainLanguages.isTranslated(this.code)) return;

        int size = Math.min(height - 2, 14);
        if (size < 6) return;
        int y = top + (height - size) / 2;
        int x = left + 2;

        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        g.blit(DT_LANG_LOGO, x, y, size, size, 0.0F, 0.0F, TEX, TEX, TEX, TEX);
    }
}
