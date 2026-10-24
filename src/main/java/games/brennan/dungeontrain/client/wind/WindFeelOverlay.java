package games.brennan.dungeontrain.client.wind;

import com.mojang.math.Axis;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.HudText;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import java.util.Locale;
import java.util.Random;

/**
 * The on-screen "you are being pushed" effects ({@link WindFeelMode}), drawn under the crosshair:
 * <ul>
 *   <li><b>C</b> near streaks — wisps at the screen edges: blowing in from the upwind edge, or out past both
 *       edges when the wind is in your face; never more than a third of the way in;</li>
 *   <li><b>D</b> upwind edge chill — a pale glow on the side of the screen the wind comes from;</li>
 *   <li><b>F</b> push chevrons — three faint arrows beside the crosshair pointing downwind.</li>
 * </ul>
 * Plus, on dev builds, the label naming the combo in force (a small line, and a big one just after K).
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class WindFeelOverlay {

    private static final int STREAKS = 12;
    /** Streak thickness, GUI pixels. */
    private static final int STREAK_THICK = 2;
    /** Streak speed, GUI pixels per second: base plus per unit of wind strength. */
    private static final float STREAK_SPEED_BASE = 220f;
    private static final float STREAK_SPEED_PER_PUSH = 9000f;
    private static final int STREAK_RGB = 0xFFFFFF;
    private static final float STREAK_ALPHA = 0.55f;

    private static final int CHILL_STRIPS = 24;
    /** Width of the chill, as a fraction of the screen width. */
    private static final float CHILL_WIDTH = 0.33f;
    private static final int CHILL_RGB = 0xE1EEFF;
    private static final float CHILL_ALPHA = 0.38f;
    /** The faint chill on both edges when the wind is head-on or from behind. */
    private static final float CHILL_ALONG = 0.4f;

    private static final int CHEVRON_RGB = 0xFFFFFF;
    private static final float CHEVRON_ALPHA = 0.75f;
    private static final int CHEVRON_GAP = 6;
    private static final int CHEVRON_SIZE = 4;
    private static final int CHEVRON_START = 12;

    private static final int LABEL_COLOR = 0xFFFFD27F;

    private static final float[] SX = new float[STREAKS];
    private static final float[] SY = new float[STREAKS];
    private static final float[] SL = new float[STREAKS];
    private static final float[] SS = new float[STREAKS];
    /** -1 = blows in from the left edge, +1 = from the right. */
    private static final int[] SIDE = new int[STREAKS];
    /** True = flies outward off its edge (wind in your face), false = blows in from its edge. */
    private static final boolean[] OUT = new boolean[STREAKS];
    private static final Random RANDOM = new Random();
    private static boolean seeded = false;
    private static long lastNanos = 0L;

    private WindFeelOverlay() {}

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerBelow(VanillaGuiLayers.CROSSHAIR,
                ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "wind_feel"),
                (LayeredDraw.Layer) WindFeelOverlay::render);
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "wind_feel_label"),
                (LayeredDraw.Layer) (g, delta) -> renderLabel(g));
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        long nanos = System.nanoTime();
        float dt = lastNanos == 0L ? 0f : Math.min(0.1f, (nanos - lastNanos) / 1.0e9f);
        lastNanos = nanos;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen != null) return;
        WindFeelMode mode = WindFeelMode.current();
        if (!mode.streaks && !mode.chill && !mode.chevrons) return;
        float pt = delta.getGameTimeDeltaPartialTick(false);
        float felt = (float) WindFeel.felt(pt);
        if (felt < 0.01f) return;
        float yaw = mc.player.getViewYRot(pt);
        float lateral = (float) WindFeelMath.lateral(WindFeel.dirX(), WindFeel.dirZ(), yaw);
        float facing = (float) WindFeelMath.facing(WindFeel.dirX(), WindFeel.dirZ(), yaw);
        float t = WindFeel.ticks() + pt;

        if (mode.chill) drawChill(g, felt, lateral, t);
        if (mode.streaks) drawStreaks(g, felt, lateral, facing, dt);
        if (mode.chevrons) drawChevrons(g, felt, lateral, facing, t);
    }

    // ---- D: upwind edge chill ----------------------------------------------------------------

    private static void drawChill(GuiGraphics g, float felt, float lateral, float t) {
        float pulse = 0.75f + 0.25f * Mth.sin(t * 0.25f);
        float a = CHILL_ALPHA * felt * pulse + 0.15f * (float) WindFeel.shudder();
        float along = (1f - Math.abs(lateral)) * CHILL_ALONG;
        // Blowing toward the right of the screen means it comes from the left.
        float left = Math.max(0f, lateral) + along;
        float right = Math.max(0f, -lateral) + along;
        int w = g.guiWidth();
        int h = g.guiHeight();
        int band = Math.max(1, (int) (w * CHILL_WIDTH));
        for (int i = 0; i < CHILL_STRIPS; i++) {
            int x0 = band * i / CHILL_STRIPS;
            int x1 = band * (i + 1) / CHILL_STRIPS;
            float fall = 1f - (float) i / CHILL_STRIPS;
            fall *= fall;
            if (left > 0f) g.fill(x0, 0, x1, h, argb(a * left * fall, CHILL_RGB));
            if (right > 0f) g.fill(w - x1, 0, w - x0, h, argb(a * right * fall, CHILL_RGB));
        }
    }

    // ---- C: near streaks ---------------------------------------------------------------------

    /**
     * Wisps at the screen edges only, inside the chill's band ({@link #CHILL_WIDTH}); nothing crosses the middle.
     * A side wind blows them in from the upwind edge (sides weighted like the chill). Wind in your face sends them
     * outward instead, off both edges, as if it is streaming past either side of you; in between, a mix.
     */
    private static void drawStreaks(GuiGraphics g, float felt, float lateral, float facing, float dt) {
        int w = g.guiWidth();
        int h = g.guiHeight();
        float band = Math.max(8f, w * CHILL_WIDTH);
        float along = (1f - Math.abs(lateral)) * CHILL_ALONG;
        float left = Math.max(0f, lateral) + along;
        float right = Math.max(0f, -lateral) + along;
        float headOn = Math.max(0f, -facing);
        if (!seeded) {
            for (int i = 0; i < STREAKS; i++) respawn(i, h, band, left, right, headOn, true);
            seeded = true;
        }
        int count = Math.round(STREAKS * felt);
        float speed = STREAK_SPEED_BASE + STREAK_SPEED_PER_PUSH * (float) WindFeel.strength();
        for (int i = 0; i < STREAKS; i++) {
            // SX = how far in from its edge the streak's head is.
            SX[i] += (OUT[i] ? -1f : 1f) * speed * SS[i] * dt;
            boolean gone = OUT[i] ? SX[i] + SL[i] < 0f : SX[i] - SL[i] > band;
            if (gone) respawn(i, h, band, left, right, headOn, false);
            if (i >= count) continue;
            boolean fromLeft = SIDE[i] < 0;
            float weight = OUT[i] ? 1f : (fromLeft ? left : right);
            if (weight <= 0f) continue;
            // Inward: head leads, tail trails toward the edge. Outward: the tail trails back into the band.
            float near = OUT[i] ? SX[i] : SX[i] - SL[i];
            float far = OUT[i] ? SX[i] + SL[i] : SX[i];
            float lo = Math.max(0f, near);
            float hi = Math.min(band, far);
            if (hi <= lo) continue;
            // Brightest at the screen edge, gone by the band's inner edge.
            float fade = 1f - (lo + hi) / (2f * band);
            float a = STREAK_ALPHA * felt * Math.min(1f, weight) * Math.max(0f, fade);
            int y = Math.round(SY[i]);
            int x0 = Math.round(fromLeft ? lo : w - hi);
            int x1 = Math.round(fromLeft ? hi : w - lo);
            int third = Math.max(1, (x1 - x0) / 3);
            // Faded ends, brighter middle.
            g.fill(x0, y, x0 + third, y + STREAK_THICK, argb(a * 0.4f, STREAK_RGB));
            g.fill(x0 + third, y, x1 - third, y + STREAK_THICK, argb(a, STREAK_RGB));
            g.fill(x1 - third, y, x1, y + STREAK_THICK, argb(a * 0.4f, STREAK_RGB));
        }
    }

    private static void respawn(int i, int h, float band, float left, float right, float headOn,
                                boolean anywhere) {
        OUT[i] = RANDOM.nextFloat() < headOn;
        float total = left + right;
        if (OUT[i]) {
            SIDE[i] = RANDOM.nextBoolean() ? -1 : 1;
        } else {
            SIDE[i] = total <= 0f || RANDOM.nextFloat() * total < left ? -1 : 1;
        }
        SY[i] = RANDOM.nextFloat() * (h - STREAK_THICK);
        SL[i] = 20f + RANDOM.nextFloat() * 35f;
        SS[i] = 0.7f + RANDOM.nextFloat() * 0.6f;
        if (anywhere) {
            SX[i] = RANDOM.nextFloat() * band;
        } else if (OUT[i]) {
            SX[i] = band + RANDOM.nextFloat() * band * 0.5f; // starts past the band's inner edge, fades in
        } else {
            SX[i] = -RANDOM.nextFloat() * band * 0.5f; // starts off-screen
        }
    }

    // ---- F: push chevrons --------------------------------------------------------------------

    private static void drawChevrons(GuiGraphics g, float felt, float lateral, float facing, float t) {
        if (felt < 0.05f) return;
        // Screen direction of the push: sideways is sideways, from behind points up (into the view).
        float angle = (float) Math.atan2(-facing, lateral);
        g.pose().pushPose();
        g.pose().translate(g.guiWidth() / 2f, g.guiHeight() / 2f, 0f);
        g.pose().mulPose(Axis.ZP.rotation(angle));
        for (int i = 0; i < 3; i++) {
            float ph = (t * 0.08f + i / 3f) % 1f;
            float a = CHEVRON_ALPHA * felt * (1f - Math.abs(ph - 0.5f) * 0.8f);
            int tip = CHEVRON_START + i * CHEVRON_GAP + Math.round(ph * 2f);
            int color = argb(a, CHEVRON_RGB);
            for (int k = 0; k <= CHEVRON_SIZE; k++) {
                g.fill(tip - k, -k, tip - k + 1, -k + 1, color);
                if (k > 0) g.fill(tip - k, k, tip - k + 1, k + 1, color);
            }
        }
        g.pose().popPose();
    }

    // ---- Dev label ---------------------------------------------------------------------------

    private static void renderLabel(GuiGraphics g) {
        if (!DungeonTrain.isDevBuild()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.font == null) return;
        boolean flash = WindFeelHotkey.flashing();
        if (!flash && !ClientWind.active()) return;
        WindFeelMode mode = WindFeelMode.current();
        String small = mode.shortLabel() + String.format(Locale.ROOT, "  felt %.2f", WindFeel.felt());
        int sw = HudText.scaledWidth(mc.font, small);
        int sx = (g.guiWidth() - sw) / 2;
        g.fill(sx - 3, 2, sx + sw + 3, 4 + HudText.scaledLineHeight(mc.font) + 1, 0x80000000);
        HudText.drawScaled(g, mc.font, small, sx, 4, LABEL_COLOR, true);
        if (flash) {
            String big = mode.label();
            g.pose().pushPose();
            g.pose().translate(g.guiWidth() / 2f, g.guiHeight() / 2f - 40f, 0f);
            g.pose().scale(2f, 2f, 1f);
            int bw = mc.font.width(big);
            g.fill(-bw / 2 - 4, -3, bw / 2 + 4, mc.font.lineHeight + 2, 0xA0000000);
            g.drawString(mc.font, big, -bw / 2, 0, LABEL_COLOR, true);
            g.pose().popPose();
        }
    }

    private static int argb(float alpha, int rgb) {
        int a = Mth.clamp(Math.round(alpha * 255f), 0, 255);
        return (a << 24) | (rgb & 0xFFFFFF);
    }
}
