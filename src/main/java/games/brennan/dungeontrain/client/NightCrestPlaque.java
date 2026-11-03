package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.systems.RenderSystem;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.CompletionistAdvancement;
import games.brennan.dungeontrain.advancement.StartAgainAdvancement;
import games.brennan.dungeontrain.advancement.TabGateways;
import games.brennan.dungeontrain.compat.AdvancementTileDecor;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;

/**
 * The Night Crest: the card Advancement Plaques shows for the star-frame advancements (each tab's
 * tab-complete, the Everything Burrito, It's Not That Simple) in place of its challenge plaque. A starry
 * navy plaque with a gold rim and DT's star frame for the icon box, star points off its ends, a pulsing
 * gold halo, twinkling sparkles and a light sweeping across it; a gold flash as it appears, held longer,
 * a gold heading and an extra chime. Hooked in by {@code AdvancementPlaqueNightCrestMixin}.
 *
 * <p>The textures are DT's own, drawn by {@code scripts/advancement-plaques/build-night-crest.py}:
 * Advancement Plaques is CC BY-NC-ND, so none of its art is copied or recoloured. Coordinates are the
 * plaque's own (its 256×32 box at 0,0), as Plaques has already translated the pose there.</p>
 */
public final class NightCrestPlaque {

    /** How much longer than a challenge plaque it stays up. */
    public static final float DURATION_SCALE = 1.5f;

    private static final ResourceLocation PLAQUE = tex("night_crest");
    private static final ResourceLocation POINTS = tex("night_crest_points");
    private static final ResourceLocation HALO = tex("night_crest_halo");
    private static final ResourceLocation FLASH = tex("night_crest_flash");
    private static final ResourceLocation SPARKLE = tex("sparkle");

    private static final int W = 256, H = 32;
    private static final int BAR_X0 = 34, BAR_X1 = 252, BAR_Y0 = 5, BAR_Y1 = 27;
    private static final long SWEEP_PERIOD_MS = 1600L;
    private static final int SWEEP_WIDTH = 8;
    private static final int SWEEP_ARGB = 0x55FFFFFF;

    /** x, y (top-left), big? — around the plaque, clear of the points. */
    private static final int[][] SPARKLES = {
        {-6, -16, 1}, {80, -18, 0}, {160, -12, 0}, {262, -14, 1}, {-14, 38, 0},
        {44, 42, 1}, {200, 44, 0}, {252, 36, 1}, {120, 40, 0}, {226, -20, 0},
    };

    private NightCrestPlaque() {}

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "textures/gui/plaque/" + name + ".png");
    }

    /** Does this advancement get the Night Crest? The ones wearing the star frame. */
    public static boolean isFor(AdvancementHolder holder) {
        return holder != null && AdvancementTileDecor.hasStarFrame(TabGateways.layout(), holder.id());
    }

    /** The plaque's heading, in place of "Challenge Complete!". */
    public static Component heading(AdvancementHolder holder) {
        boolean everything = CompletionistAdvancement.ID.equals(holder.id()) || StartAgainAdvancement.ID.equals(holder.id());
        String key = everything ? "dungeontrain.plaque.everything_complete" : "dungeontrain.plaque.tab_complete";
        return Component.translatable(key).withStyle(ChatFormatting.GOLD);
    }

    /**
     * Everything under the plaque's text and icon: halo, points, the plaque itself, the sweep and the
     * sparkles. {@code elapsedMs} is the plaque's own clock. Keeps the fade alpha Plaques set.
     */
    public static void drawBackground(GuiGraphics g, long elapsedMs) {
        float alpha = RenderSystem.getShaderColor()[3];
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        float pulse = 0.8f + 0.2f * (float) Math.sin(elapsedMs / 300.0);
        g.setColor(1.0f, 1.0f, 1.0f, alpha * pulse);
        g.blit(HALO, -32, -24, 320, 80, 0, 0, 320, 80, 320, 80);

        g.setColor(1.0f, 1.0f, 1.0f, alpha);
        g.blit(POINTS, -16, -8, 288, 48, 0, 0, 288, 48, 288, 48);
        g.blit(PLAQUE, 0, 0, W, H, 0, 0, W, H, W, H);
        drawSweep(g, elapsedMs);

        for (int i = 0; i < SPARKLES.length; i++) {
            int[] s = SPARKLES[i];
            float twinkle = (float) Math.sin(elapsedMs / 250.0 + i * 1.7);
            if (twinkle <= 0) continue;
            g.setColor(1.0f, 1.0f, 1.0f, alpha * twinkle);
            if (s[2] == 1) g.blit(SPARKLE, s[0], s[1], 7, 7, 8, 0, 7, 7, 16, 8);
            else g.blit(SPARKLE, s[0], s[1], 5, 5, 0, 0, 5, 5, 16, 8);
        }
        g.setColor(1.0f, 1.0f, 1.0f, alpha);
    }

    /** A diagonal band of light crossing the bar once per {@link #SWEEP_PERIOD_MS}. */
    private static void drawSweep(GuiGraphics g, long elapsedMs) {
        int travel = BAR_X1 - BAR_X0 + 2 * SWEEP_WIDTH + 16;
        int front = BAR_X0 - SWEEP_WIDTH - 8 + (int) (Math.floorMod(elapsedMs, SWEEP_PERIOD_MS) * travel / SWEEP_PERIOD_MS);
        for (int y = BAR_Y0; y < BAR_Y1; y++) {
            int x0 = Math.max(BAR_X0, front - (y - H / 2) / 2);
            int x1 = Math.min(BAR_X1, front - (y - H / 2) / 2 + SWEEP_WIDTH);
            if (x0 < x1) g.fill(x0, y, x1, y + 1, SWEEP_ARGB);
        }
        g.flush();
    }

    /** The gold flash as it appears, in Plaques' effect box (-16,-16, 288×64), at the alpha it set. */
    public static void drawFlash(GuiGraphics g) {
        g.blit(FLASH, -16, -16, W + 32, H + 32, 0, 0, W + 32, H + 32, W + 32, H + 32);
    }

    /** The extra chime, on top of the challenge sound. */
    public static void playChime() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_RESONATE, 1.2f, 0.9f));
    }
}
