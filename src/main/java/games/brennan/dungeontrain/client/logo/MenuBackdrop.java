package games.brennan.dungeontrain.client.logo;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * The main menu's backdrop, behind the ring and the buttons, dimmed so they stay readable:
 * <ol>
 *   <li>the Live Feed, while someone is streaming and it can be shown ({@link MenuLiveFeed});</li>
 *   <li>otherwise a slow slideshow of the community's tributed photos, each crossfading into the
 *       next and easing in as it shows, credited to its photographer ({@link MenuPhotoSource});</li>
 *   <li>the ride photos bundled in the jar while the tributes can't be reached.</li>
 * </ol>
 * Only the photo on screen and the one coming are held; a photo stays up until the next has loaded.
 * GPU memory goes once the menu hasn't drawn for a few seconds ({@link #releaseIfIdle}).
 */
public final class MenuBackdrop {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final float SHOW_SECONDS = 9f;
    private static final float FADE_SECONDS = 2f;
    /** How far a photo eases in over its time on screen. */
    private static final float ZOOM = 0.07f;
    /** Dim over the backdrop, as on the website's (brightness about 0.65). */
    private static final int DIM = 0x5A000000;
    private static final int SHADE_TOP = 0x8C0B0A14;
    private static final int SHADE_BOTTOM = 0x330B0A14;
    private static final int CAPTION = 0xFFD8DCE0;
    private static final int LIVE_RED = 0xFFE5484D;
    private static final long IDLE_RELEASE_NANOS = 5_000_000_000L;

    private static final MenuBackdrop INSTANCE = new MenuBackdrop();

    private record Photo(ResourceLocation id, int width, int height, @Nullable String author) {}

    private final MenuPhotoSource source = new MenuPhotoSource();
    @Nullable private Photo current, coming;
    @Nullable private CompletableFuture<MenuPhotoSource.Loaded> loading;
    private float shownFor;       // seconds the current photo has been up
    private float fade = -1;      // 0..1 while the coming photo crossfades in, -1 otherwise
    private int uploads;
    private long lastNanos;

    private MenuBackdrop() {}

    public static MenuBackdrop get() { return INSTANCE; }

    /** Draws the backdrop over the whole screen; the caller has already filled it with the plain gradient. */
    public void draw(GuiGraphics g, int width, int height, int tick, float partialTick) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        Font font = Minecraft.getInstance().font;
        if (MenuLiveFeed.draw(g, width, height, tick, partialTick)) {
            shade(g, width, height);
            String who = MenuLiveFeed.streamer();
            caption(g, font, width, Component.translatable(who == null || who.isBlank()
                    ? "gui.dungeontrain.menu.backdrop.live" : "gui.dungeontrain.menu.backdrop.live_named", who), LIVE_RED);
            return;
        }
        advance(dt);
        if (current == null) return;
        float progress = shownFor / (SHOW_SECONDS + FADE_SECONDS);
        drawPhoto(g, current, width, height, progress, 1f);
        if (coming != null && fade >= 0) {
            drawPhoto(g, coming, width, height, fade * FADE_SECONDS / (SHOW_SECONDS + FADE_SECONDS), fade * fade * (3 - 2 * fade));
        }
        shade(g, width, height);
        Photo credited = coming != null && fade > 0.5f ? coming : current;
        if (credited.author() != null) {
            caption(g, font, width, Component.translatable("gui.dungeontrain.menu.backdrop.photo_by", credited.author()), CAPTION);
        }
    }

    /** Frees the photos and the live decode once the menu hasn't drawn for a while. Render thread. */
    public void releaseIfIdle() {
        if (lastNanos == 0 || System.nanoTime() - lastNanos < IDLE_RELEASE_NANOS) return;
        MenuLiveFeed.close();
        MenuLiveFeed.forgetPoll();
        release(current);
        release(coming);
        current = null;
        coming = null;
        fade = -1;
        shownFor = 0;
        lastNanos = 0;
    }

    /** Loads the next photo ahead, and runs the show: up for SHOW_SECONDS, then a crossfade once the next is in. */
    private void advance(float dt) {
        if (loading == null && (current == null || coming == null)) loading = source.next();
        if (loading != null && loading.isDone()) {
            Photo loaded = upload(loading);
            loading = null;
            if (loaded != null) {
                if (current == null) current = loaded;
                else coming = loaded;
            }
        }
        shownFor += dt;
        if (fade < 0 && coming != null && shownFor >= SHOW_SECONDS) fade = 0;
        if (fade >= 0) {
            fade += dt / FADE_SECONDS;
            if (fade >= 1) {
                release(current);
                current = coming;
                coming = null;
                fade = -1;
                shownFor = FADE_SECONDS;
            }
        }
    }

    @Nullable
    private Photo upload(CompletableFuture<MenuPhotoSource.Loaded> done) {
        MenuPhotoSource.Loaded loaded;
        try {
            loaded = done.join();
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Menu backdrop photo unavailable ({}); skipped", e.toString());
            return null;
        }
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "menu_backdrop/photo_" + uploads++);
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(loaded.image()));
        return new Photo(id, loaded.image().getWidth(), loaded.image().getHeight(), loaded.author());
    }

    private static void release(@Nullable Photo photo) {
        if (photo != null) Minecraft.getInstance().getTextureManager().release(photo.id());
    }

    private static void shade(GuiGraphics g, int width, int height) {
        g.fill(0, 0, width, height, DIM);
        g.fillGradient(0, 0, width, height, SHADE_TOP, SHADE_BOTTOM);
    }

    /** A small credit at the top right, clear of the version line, splash and buttons. */
    private static void caption(GuiGraphics g, Font font, int width, Component text, int colour) {
        g.drawString(font, text, width - font.width(text) - 4, 4, colour, true);
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
