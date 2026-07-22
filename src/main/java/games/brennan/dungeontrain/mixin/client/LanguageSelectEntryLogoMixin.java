package games.brennan.dungeontrain.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import games.brennan.dungeontrain.client.DungeonTrainLanguages;
import games.brennan.dungeontrain.client.localization.LocalizationCreditRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
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
 * icon at the left edge of the row after the vanilla name render (TAIL). Translations that have
 * not been human-reviewed show the logo faded plus a blue "AI" label beside it. Non-translated
 * languages are untouched.</p>
 */
@Mixin(targets = "net.minecraft.client.gui.screens.options.LanguageSelectScreen$LanguageSelectionList$Entry")
public abstract class LanguageSelectEntryLogoMixin {

    @Shadow @Final String code;

    private static final ResourceLocation DT_LANG_LOGO =
        ResourceLocation.fromNamespaceAndPath("dungeontrain", "textures/gui/language_logo.png");
    /** Source texture is square 64x64. */
    private static final int TEX = 64;
    /** Opacity for languages whose translation has not been human-reviewed. */
    private static final float UNREVIEWED_ALPHA = 0.35F;
    /** Colour of the "AI" label — blue, faded to the same ~35% alpha as the unreviewed logo. */
    private static final int AI_LABEL_COLOR = 0x5955AAFF;
    /** Gap in px between the icon and the "AI" label. */
    private static final int AI_LABEL_GAP = 2;
    /** Text scale for the "AI" label — smaller than the row's language name. */
    private static final float AI_LABEL_SCALE = 0.6F;

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

        // Human-reviewed translations show the logo solid; machine-only ones are faded right down
        // and get an explicit blue "AI" label next to the icon.
        boolean humanReviewed = LocalizationCreditRegistry.isHumanReviewed(this.code);
        float alpha = humanReviewed ? 1.0F : UNREVIEWED_ALPHA;

        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        g.blit(DT_LANG_LOGO, x, y, size, size, 0.0F, 0.0F, TEX, TEX, TEX, TEX);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        if (!humanReviewed) {
            Font font = Minecraft.getInstance().font;
            float labelX = x + size + AI_LABEL_GAP;
            float labelY = top + (height - font.lineHeight * AI_LABEL_SCALE) / 2.0F + 1.0F;
            g.pose().pushPose();
            g.pose().translate(labelX, labelY, 0.0F);
            g.pose().scale(AI_LABEL_SCALE, AI_LABEL_SCALE, 1.0F);
            g.drawString(font, "AI", 0, 0, AI_LABEL_COLOR, true);
            g.pose().popPose();
        }
    }
}
