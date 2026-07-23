package games.brennan.dungeontrain.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.client.DungeonTrainLanguages;
import games.brennan.dungeontrain.client.localization.LocalizationCreditRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.OptionalDouble;

/**
 * Badges each language row in the vanilla language-selection list with the Dungeon Train logo
 * when the mod ships a translation for that language (see {@link DungeonTrainLanguages}), so
 * players can see which languages Dungeon Train is localized into.
 *
 * <p>Targets the inner {@code LanguageSelectScreen.LanguageSelectionList.Entry} and draws a small
 * icon at the left edge of the row after the vanilla name render (TAIL). Translations that have
 * not been human-reviewed show the logo faded plus a blue "AI" label beside it. Non-translated
 * languages are untouched.</p>
 *
 * <p>When the locale's credit carries the generated AI counts (see
 * {@code LocalizationCreditRegistry#aiFraction}), a blue ring is drawn around the logo whose
 * filled fraction of the circumference is the locale's AI-unreviewed share — a full circle means
 * entirely machine translation no human has reviewed, a thin arc means mostly human-covered. The
 * arc runs clockwise from 12 o'clock over a faint full-circle track so small fractions still read
 * as a gauge.</p>
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

    /** Quad segments for a full 360° ring; arcs use a proportional share. */
    private static final int RING_SEGMENTS = 40;
    /** Radial width of the ring band, in GUI px. */
    private static final float RING_THICKNESS = 1.5F;
    /** Filled-arc colour — the "AI" blue at full opacity. */
    private static final int RING_FILL_COLOR = 0xFF55AAFF;
    /** Full-circle track behind the arc — same blue, faint. */
    private static final int RING_TRACK_COLOR = 0x3355AAFF;

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

        OptionalDouble aiFraction = LocalizationCreditRegistry.aiFraction(this.code);
        boolean ring = aiFraction.isPresent() && aiFraction.getAsDouble() > 0.0;

        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        if (ring) {
            // Inset the logo so the ring band caps its corners into a circular badge.
            g.blit(DT_LANG_LOGO, x + 1, y + 1, size - 2, size - 2, 0.0F, 0.0F, TEX, TEX, TEX, TEX);
        } else {
            g.blit(DT_LANG_LOGO, x, y, size, size, 0.0F, 0.0F, TEX, TEX, TEX, TEX);
        }
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        // Ring AFTER the shader-colour reset — its colours must not inherit the logo's fade.
        if (ring) {
            float fraction = Mth.clamp((float) aiFraction.getAsDouble(), 0.0F, 1.0F);
            float cx = x + size / 2.0F;
            float cy = y + size / 2.0F;
            float outerR = size / 2.0F + 1.0F;
            float innerR = outerR - RING_THICKNESS;
            VertexConsumer vc = g.bufferSource().getBuffer(RenderType.gui());
            Matrix4f pose = g.pose().last().pose();
            dungeontrain$emitRingArc(vc, pose, cx, cy, innerR, outerR,
                Mth.TWO_PI, RING_SEGMENTS, RING_TRACK_COLOR);
            dungeontrain$emitRingArc(vc, pose, cx, cy, innerR, outerR,
                fraction * Mth.TWO_PI, Math.max(1, Mth.ceil(fraction * RING_SEGMENTS)),
                RING_FILL_COLOR);
            g.flush(); // deterministic draw before the label / the next entry's blit
        }

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

    /**
     * Emits an annular arc as {@code segments} quads into a {@link RenderType#gui()} buffer:
     * 12 o'clock start, clockwise sweep of {@code sweepRad} radians. Per-quad vertex order
     * mirrors vanilla {@code GuiGraphics.fill}'s winding (backface culling is on for the
     * gui render type).
     */
    @Unique
    private static void dungeontrain$emitRingArc(VertexConsumer vc, Matrix4f pose, float cx,
                                                 float cy, float innerR, float outerR,
                                                 float sweepRad, int segments, int argb) {
        for (int i = 0; i < segments; i++) {
            float t0 = sweepRad * i / segments;
            float t1 = sweepRad * (i + 1) / segments;
            float sin0 = Mth.sin(t0);
            float cos0 = Mth.cos(t0);
            float sin1 = Mth.sin(t1);
            float cos1 = Mth.cos(t1);
            vc.addVertex(pose, cx + outerR * sin0, cy - outerR * cos0, 0.0F).setColor(argb);
            vc.addVertex(pose, cx + innerR * sin0, cy - innerR * cos0, 0.0F).setColor(argb);
            vc.addVertex(pose, cx + innerR * sin1, cy - innerR * cos1, 0.0F).setColor(argb);
            vc.addVertex(pose, cx + outerR * sin1, cy - outerR * cos1, 0.0F).setColor(argb);
        }
    }
}
