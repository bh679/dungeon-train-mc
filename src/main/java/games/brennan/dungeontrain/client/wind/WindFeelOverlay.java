package games.brennan.dungeontrain.client.wind;

import com.mojang.math.Axis;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
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
 * the way in, at the strength the player set (Options, Train tab: OFF or tiers 1 to 6, {@link WindEffectsOption}): a
 * side wind blows them in from the upwind edge; wind in your face sends them out past both edges,
 * wind at your back in from both; and the more the wind runs along the view, the more they follow perspective.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class WindFeelOverlay {

    /** Streaks at the default tier ({@link ClientDisplayConfig#DEFAULT_WIND_SCREEN_EFFECTS}); the pool holds twice. */
    private static final int BASE_STREAKS = 12;
    private static final int STREAKS = BASE_STREAKS * 2;
    /** Brightest a streak may draw at any tier. */
    private static final float MAX_ALPHA = 0.9f;
    /** Streak thickness, GUI pixels. */
    private static final float STREAK_THICK = 2f;
    /**
     * With the wind along the view, a streak's size at the screen edge (close to you) and at the middle (far away),
     * relative to its flat size.
     */
    private static final float STREAK_NEAR_SCALE = 1.5f;
    private static final float STREAK_FAR_SCALE = 0.3f;
    /** Streak speed, GUI pixels per second: base plus per unit of wind strength. */
    private static final float STREAK_SPEED_BASE = 220f;
    private static final float STREAK_SPEED_PER_PUSH = 9000f;
    private static final int STREAK_RGB = 0xFFFFFF;
    private static final float STREAK_ALPHA = 0.55f;
    /** Width of the band at each edge the streaks stay in, as a fraction of the screen width. */
    private static final float BAND_WIDTH = 0.33f;

    private static final float[] SX = new float[STREAKS];
    private static final float[] SY = new float[STREAKS];
    private static final float[] SL = new float[STREAKS];
    private static final float[] SS = new float[STREAKS];
    /** -1 = blows in from the left edge, +1 = from the right. */
    private static final int[] SIDE = new int[STREAKS];
    /** True = flies outward off its edge, false = blows in from it (always its edge's current flow). */
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
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        long nanos = System.nanoTime();
        float dt = lastNanos == 0L ? 0f : Math.min(0.1f, (nanos - lastNanos) / 1.0e9f);
        lastNanos = nanos;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen != null) return;
        int tier = ClientDisplayConfig.getWindScreenEffects();
        if (tier <= 0) return;
        float pt = delta.getGameTimeDeltaPartialTick(false);
        float felt = (float) WindFeel.felt(pt);
        if (felt < 0.01f) return;
        float yaw = mc.player.getViewYRot(pt);
        float lateral = (float) WindFeelMath.lateral(WindFeel.dirX(), WindFeel.dirZ(), yaw);
        float facing = (float) WindFeelMath.facing(WindFeel.dirX(), WindFeel.dirZ(), yaw);
        drawStreaks(g, felt, lateral, facing, dt,
                tier / (float) ClientDisplayConfig.DEFAULT_WIND_SCREEN_EFFECTS);
    }

    /**
     * Wisps at the screen edges only, inside the edge band ({@link #BAND_WIDTH}); nothing crosses the middle.
     * A side wind blows them in from the upwind edge, flat across the screen. Wind
     * in your face sends them outward instead, off both edges; wind at your back sends them inward. Each edge
     * flows one way at a time and fades out just before it turns around ({@link WindFeelMath#edgeWeight}), so
     * an edge never has streaks going both ways. The more the
     * wind runs along the view, the more they follow perspective: each one runs on a line toward the middle of
     * the screen, bigger and faster near the edge (close to you), smaller toward the middle (far away).
     */
    /**
     * {@code tierScale} is the player's setting over the default tier (1 = the default look): it scales how many
     * streaks show, how bright and how thick they draw.
     */
    private static void drawStreaks(GuiGraphics g, float felt, float lateral, float facing, float dt,
                                    float tierScale) {
        int w = g.guiWidth();
        int h = g.guiHeight();
        float half = w / 2f;
        float band = Math.max(8f, w * BAND_WIDTH);
        // Each edge has one direction at a time, and fades out just before it flips (WindFeelMath#edgeWeight).
        float left = (float) WindFeelMath.edgeWeight(lateral, facing, true);
        float right = (float) WindFeelMath.edgeWeight(lateral, facing, false);
        boolean outLeft = WindFeelMath.edgeFlow(lateral, facing, true) > 0.0;
        boolean outRight = WindFeelMath.edgeFlow(lateral, facing, false) > 0.0;
        float depthMix = Math.min(1f, Math.abs(facing));
        if (!seeded) {
            for (int i = 0; i < STREAKS; i++) respawn(i, h, band, left, right, outLeft, outRight, true);
            seeded = true;
        }
        int count = Math.min(STREAKS, Math.round(BASE_STREAKS * tierScale * felt));
        float alphaScale = 0.5f + 0.5f * tierScale;
        float thickScale = 0.75f + 0.25f * tierScale;
        float speed = STREAK_SPEED_BASE + STREAK_SPEED_PER_PUSH * (float) WindFeel.strength();
        for (int i = 0; i < STREAKS; i++) {
            // SX = how far in from its edge the streak's head is. Close to the edge (close to you) it moves faster.
            float near0 = 1f - Math.max(0f, SX[i]) / half;
            float pace = 1f + depthMix * (near0 - 0.5f);
            SX[i] += (OUT[i] ? -1f : 1f) * speed * SS[i] * pace * dt;
            float len = SL[i] * scaleAt(SX[i], half, depthMix);
            boolean gone = OUT[i] ? SX[i] + len < 0f : SX[i] - len > band;
            // Its edge has turned around: it was already faded out, so start it again the new way.
            boolean turned = OUT[i] != (SIDE[i] < 0 ? outLeft : outRight);
            if (gone || turned) respawn(i, h, band, left, right, outLeft, outRight, false);
            if (i >= count) continue;
            boolean fromLeft = SIDE[i] < 0;
            float weight = fromLeft ? left : right;
            if (weight <= 0f) continue;
            // Inward: head leads, tail trails toward the edge. Outward: the tail trails back into the band.
            float lo = Math.max(0f, OUT[i] ? SX[i] : SX[i] - len);
            float hi = Math.min(band, OUT[i] ? SX[i] + len : SX[i]);
            if (hi <= lo) continue;
            // Brightest at the screen edge, gone by the band's inner edge.
            float fade = 1f - (lo + hi) / (2f * band);
            float a = Math.min(MAX_ALPHA, STREAK_ALPHA * alphaScale * felt * Math.min(1f, weight) * Math.max(0f, fade));
            float thick = STREAK_THICK * thickScale * scaleAt((lo + hi) / 2f, half, depthMix);
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

    private static void respawn(int i, int h, float band, float left, float right, boolean outLeft,
                                boolean outRight, boolean anywhere) {
        float total = left + right;
        SIDE[i] = (total <= 0f ? RANDOM.nextBoolean() : RANDOM.nextFloat() * total < left) ? -1 : 1;
        OUT[i] = SIDE[i] < 0 ? outLeft : outRight;
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
