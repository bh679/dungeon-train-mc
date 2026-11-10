package games.brennan.dungeontrain.client.logo;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The main menu's backdrop: Dungeon Train ride photos (the website's admin-picked ones) as a slow
 * slideshow, each crossfading into the next and easing in a little as it shows, dimmed so the
 * ring, wordmark and buttons on top stay readable.
 *
 * <p>The photos ship as JPEGs to keep the jar small; Minecraft only reads PNG textures, so they
 * are decoded with ImageIO off the render thread and uploaded as dynamic textures. Their GPU memory
 * goes again once the menu hasn't drawn them for a few seconds ({@link #releaseIfIdle}).</p>
 */
public final class MenuBackdrop {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PHOTOS = 7;
    private static final float SHOW_SECONDS = 9f;
    private static final float FADE_SECONDS = 2f;
    /** How far a photo eases in over its time on screen. */
    private static final float ZOOM = 0.07f;
    /** Dim over the photos, as on the website's backdrops (brightness about 0.65). */
    private static final int DIM = 0x5A000000;
    private static final int SHADE_TOP = 0x8C0B0A14;
    private static final int SHADE_BOTTOM = 0x330B0A14;
    private static final long IDLE_RELEASE_NANOS = 5_000_000_000L;

    private static final MenuBackdrop INSTANCE = new MenuBackdrop();

    private CompletableFuture<List<NativeImage>> decoding;
    private final List<Photo> photos = new ArrayList<>();
    private float clock;          // seconds of slideshow shown so far
    private long lastNanos;

    private record Photo(ResourceLocation id, int width, int height) {}

    private MenuBackdrop() {}

    public static MenuBackdrop get() { return INSTANCE; }

    /**
     * Draws the backdrop over the whole screen. Before the photos are ready (or if they can't be
     * read) only {@code fallback} shows: the caller fills the screen with it first.
     */
    public void draw(GuiGraphics g, int width, int height) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        if (!ready()) return;
        clock += dt;
        float t = clock;
        int count = photos.size();
        int index = (int) (t / SHOW_SECONDS);
        float into = t - index * SHOW_SECONDS;
        drawPhoto(g, photos.get(index % count), width, height, (into + FADE_SECONDS) / (SHOW_SECONDS + FADE_SECONDS), 1f);
        if (into > SHOW_SECONDS - FADE_SECONDS) {
            float fade = (into - (SHOW_SECONDS - FADE_SECONDS)) / FADE_SECONDS;
            drawPhoto(g, photos.get((index + 1) % count), width, height, fade * FADE_SECONDS / (SHOW_SECONDS + FADE_SECONDS),
                    fade * fade * (3 - 2 * fade));
        }
        g.fill(0, 0, width, height, DIM);
        g.fillGradient(0, 0, width, height, SHADE_TOP, SHADE_BOTTOM);
    }

    /** Frees the photos' textures once nothing has drawn them for a while. Render thread. */
    public void releaseIfIdle() {
        if (photos.isEmpty() || System.nanoTime() - lastNanos < IDLE_RELEASE_NANOS) return;
        for (Photo p : photos) Minecraft.getInstance().getTextureManager().release(p.id());
        photos.clear();
        decoding = null;
        lastNanos = 0;
    }

    /** Starts decoding on first use; uploads the photos once decoded. True once there is something to draw. */
    private boolean ready() {
        if (!photos.isEmpty()) return true;
        if (decoding == null) decoding = CompletableFuture.supplyAsync(MenuBackdrop::decodeAll);
        if (!decoding.isDone()) return false;
        List<NativeImage> images = decoding.getNow(List.of());
        if (images.isEmpty()) return false;
        for (int i = 0; i < images.size(); i++) {
            NativeImage image = images.get(i);
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "menu_backdrop/photo_" + i);
            photos.add(new Photo(id, image.getWidth(), image.getHeight()));
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
        }
        Collections.rotate(photos, (int) (Math.random() * photos.size())); // a different first photo each launch
        clock = 0f;
        return true;
    }

    private static List<NativeImage> decodeAll() {
        List<NativeImage> out = new ArrayList<>();
        for (int i = 1; i <= PHOTOS; i++) {
            ResourceLocation file = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID,
                    String.format("textures/gui/menu_backdrop/ride-%02d.jpg", i));
            try (InputStream in = Minecraft.getInstance().getResourceManager().open(file)) {
                BufferedImage image = ImageIO.read(in);
                if (image == null) throw new IllegalStateException("not an image ImageIO can read");
                out.add(toNative(image));
            } catch (Exception e) {
                LOGGER.warn("[DungeonTrain] Menu backdrop photo {} unavailable ({}); skipped", file, e.toString());
            }
        }
        return out;
    }

    private static NativeImage toNative(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        NativeImage out = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = argb[y * w + x];
                // NativeImage holds ABGR.
                out.setPixelRGBA(x, y, 0xFF000000 | (c & 0xFF) << 16 | (c & 0xFF00) | (c >> 16 & 0xFF));
            }
        }
        return out;
    }

    /** One photo covering the screen, eased in by {@code progress} (0..1 of its time on screen), at {@code alpha}. */
    private static void drawPhoto(GuiGraphics g, Photo photo, int width, int height, float progress, float alpha) {
        if (alpha <= 0f) return;
        float cover = Math.max((float) width / photo.width(), (float) height / photo.height());
        float scale = cover * (1f + ZOOM * Mth.clamp(progress, 0f, 1f));
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        g.pose().pushPose();
        g.pose().translate(width / 2f, height / 2f, 0f);
        g.pose().scale(scale, scale, 1f);
        g.blit(photo.id(), -photo.width() / 2, -photo.height() / 2, 0f, 0f, photo.width(), photo.height(), photo.width(), photo.height());
        g.pose().popPose();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }
}
