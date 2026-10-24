package games.brennan.dungeontrain.client.wind;

import com.mojang.math.Axis;
import games.brennan.dungeontrain.DungeonTrain;
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

import java.util.Random;

/**
 * Edge streaks: while the wind is on the player out in the open ({@link WindFeel}), a few wisps blow past the
 * screen edges so the push reads even before it moves you. Drawn under the crosshair, never more than a third of
 * the way in: a side wind blows them in from the upwind edge; wind in your face sends them out past both edges,
 * wind at your back in from both; and the more the wind runs along the view, the more they follow perspective.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class WindFeelOverlay {

    private static final int STREAKS = 12;
    /** Streak thickness, GUI pixels. */
    private static final float STREAK_THICK = 2f;
    /**
     * With the wind along the view, a streak's size at the screen edge (close to you) and at the middle (far away),
     * relative to its flat size.
     */
    private static final float STREAK_NEAR_SCALE = 1.5f;
    private static final float STREAK_FAR_SCALE = 0.3f;
    /** How strongly wind along the view (head-on or behind) fills both edges with streaks. */
    private static final float STREAK_ALONG = 0.8f;
    /** Streak speed, GUI pixels per second: base plus per unit of wind strength. */
    private static final float STREAK_SPEED_BASE = 220f;
    private static final float STREAK_SPEED_PER_PUSH = 9000f;
    private static final int STREAK_RGB = 0xFFFFFF;
    private static final float STREAK_ALPHA = 0.55f;
    /** {@code facing} below which the streaks switch to flying outward, and above which they switch back. */
    private static final float OUTWARD_ENTER = -0.45f;
    private static final float OUTWARD_EXIT = -0.25f;
    /** Width of the band at each edge the streaks stay in, as a fraction of the screen width. */
    private static final float BAND_WIDTH = 0.33f;

    private static final float[] SX = new float[STREAKS];
    private static final float[] SY = new float[STREAKS];
    private static final float[] SL = new float[STREAKS];
    private static final float[] SS = new float[STREAKS];
    /** -1 = blows in from the left edge, +1 = from the right. */
    private static final int[] SIDE = new int[STREAKS];
    /** True = flies outward off its edge, false = blows in from it (always the current {@link #outward}). */
    private static final boolean[] OUT = new boolean[STREAKS];
    private static final Random RANDOM = new Random();
    private static boolean seeded = false;
    /** The flow in force: true = out past both edges (wind in your face), false = in from the edges. */
    private static boolean outward = false;
    private static long lastNanos = 0L;

    private WindFeelOverlay() {}

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerBelow(VanillaGuiLayers.CROSSHAIR,
                ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "wind_feel"),
                (LayeredDraw.Layer) WindFeelOverlay::render);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        long nanos = System.nanoTime();
        float dt = lastNanos == 0L ? 0f : Math.min(0.1f, (nanos - lastNanos) / 1.0e9f);
        lastNanos = nanos;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen != null) return;
        float pt = delta.getGameTimeDeltaPartialTick(false);
        float felt = (float) WindFeel.felt(pt);
        if (felt < 0.01f) return;
        float yaw = mc.player.getViewYRot(pt);
        float lateral = (float) WindFeelMath.lateral(WindFeel.dirX(), WindFeel.dirZ(), yaw);
        float facing = (float) WindFeelMath.facing(WindFeel.dirX(), WindFeel.dirZ(), yaw);
        drawStreaks(g, felt, lateral, facing, dt);
    }

    /**
     * Wisps at the screen edges only, inside the edge band ({@link #BAND_WIDTH}); nothing crosses the middle.
     * A side wind blows them in from the upwind edge, flat across the screen. Wind
     * mostly in your face sends them all outward instead, off both edges; wind at your back sends them inward.
     * Every streak follows the same flow, so a side never has streaks going both ways. The more the
     * wind runs along the view, the more they follow perspective: each one runs on a line toward the middle of
     * the screen, bigger and faster near the edge (close to you), smaller toward the middle (far away).
     */
    private static void drawStreaks(GuiGraphics g, float felt, float lateral, float facing, float dt) {
        int w = g.guiWidth();
        int h = g.guiHeight();
        float half = w / 2f;
        float band = Math.max(8f, w * BAND_WIDTH);
        float along = (1f - Math.abs(lateral)) * STREAK_ALONG;
        float left = Math.max(0f, lateral) + along;
        float right = Math.max(0f, -lateral) + along;
        float depthMix = Math.min(1f, Math.abs(facing));
        // One flow for the whole screen, so a side never has streaks going both ways: outward while the wind is
        // in your face, inward otherwise. Hysteresis keeps it from flickering at the turn; on a change every
        // streak starts again from where the new flow begins.
        boolean flow = outward ? facing < OUTWARD_EXIT : facing < OUTWARD_ENTER;
        if (!seeded || flow != outward) {
            outward = flow;
            for (int i = 0; i < STREAKS; i++) respawn(i, h, band, left, right, !seeded);
            seeded = true;
        }
        int count = Math.round(STREAKS * felt);
        float speed = STREAK_SPEED_BASE + STREAK_SPEED_PER_PUSH * (float) WindFeel.strength();
        for (int i = 0; i < STREAKS; i++) {
            // SX = how far in from its edge the streak's head is. Close to the edge (close to you) it moves faster.
            float near0 = 1f - Math.max(0f, SX[i]) / half;
            float pace = 1f + depthMix * (near0 - 0.5f);
            SX[i] += (OUT[i] ? -1f : 1f) * speed * SS[i] * pace * dt;
            float len = SL[i] * scaleAt(SX[i], half, depthMix);
            boolean gone = OUT[i] ? SX[i] + len < 0f : SX[i] - len > band;
            if (gone) respawn(i, h, band, left, right, false);
            if (i >= count) continue;
            boolean fromLeft = SIDE[i] < 0;
            float weight = OUT[i] ? 1f : (fromLeft ? left : right);
            if (weight <= 0f) continue;
            // Inward: head leads, tail trails toward the edge. Outward: the tail trails back into the band.
            float lo = Math.max(0f, OUT[i] ? SX[i] : SX[i] - len);
            float hi = Math.min(band, OUT[i] ? SX[i] + len : SX[i]);
            if (hi <= lo) continue;
            // Brightest at the screen edge, gone by the band's inner edge.
            float fade = 1f - (lo + hi) / (2f * band);
            float a = STREAK_ALPHA * felt * Math.min(1f, weight) * Math.max(0f, fade);
            float thick = STREAK_THICK * scaleAt((lo + hi) / 2f, half, depthMix);
            drawStreak(g, fromLeft, w, h, SY[i], lo, hi, half, depthMix, thick, a);
        }
    }

    /** Perspective size at {@code u} in from the edge: 1 for a flat side wind, bigger at the edge along the view. */
    private static float scaleAt(float u, float half, float depthMix) {
        float near = 1f - Math.max(0f, Math.min(half, u)) / half;
        return 1f + depthMix * (STREAK_FAR_SCALE + (STREAK_NEAR_SCALE - STREAK_FAR_SCALE) * near - 1f);
    }

    /** Screen point {@code u} in from the edge on a streak meeting the edge at height {@code yEdge}. */
    private static float yAt(float yEdge, float u, float h, float half, float depthMix) {
        return yEdge + (h / 2f - yEdge) * (u / half) * depthMix;
    }

    private static void drawStreak(GuiGraphics g, boolean fromLeft, int w, int h, float yEdge, float lo, float hi,
                                   float half, float depthMix, float thick, float alpha) {
        float xa = fromLeft ? lo : w - lo;
        float xb = fromLeft ? hi : w - hi;
        float ya = yAt(yEdge, lo, h, half, depthMix);
        float yb = yAt(yEdge, hi, h, half, depthMix);
        float dx = xb - xa;
        float dy = yb - ya;
        int len = Math.round((float) Math.sqrt(dx * dx + dy * dy));
        if (len < 2) return;
        g.pose().pushPose();
        g.pose().translate((xa + xb) / 2f, (ya + yb) / 2f, 0f);
        g.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(dy, dx)));
        g.pose().scale(1f, Math.max(1f, thick), 1f);
        g.pose().translate(0f, -0.5f, 0f);
        int x0 = -len / 2;
        int x1 = x0 + len;
        int third = Math.max(1, len / 3);
        // Faded ends, brighter middle.
        g.fill(x0, 0, x0 + third, 1, argb(alpha * 0.4f, STREAK_RGB));
        g.fill(x0 + third, 0, x1 - third, 1, argb(alpha, STREAK_RGB));
        g.fill(x1 - third, 0, x1, 1, argb(alpha * 0.4f, STREAK_RGB));
        g.pose().popPose();
    }

    private static void respawn(int i, int h, float band, float left, float right, boolean anywhere) {
        OUT[i] = outward;
        float total = left + right;
        if (OUT[i]) {
            SIDE[i] = RANDOM.nextBoolean() ? -1 : 1;
        } else {
            SIDE[i] = total <= 0f || RANDOM.nextFloat() * total < left ? -1 : 1;
        }
        SY[i] = RANDOM.nextFloat() * h;
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

    private static int argb(float alpha, int rgb) {
        int a = Mth.clamp(Math.round(alpha * 255f), 0, 255);
        return (a << 24) | (rgb & 0xFFFFFF);
    }
}
