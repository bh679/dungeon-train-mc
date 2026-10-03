package games.brennan.dungeontrain.client.deathphotos;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import io.github.mortuusars.exposure.ExposureClient;
import io.github.mortuusars.exposure.client.image.renderable.RenderableImage;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.level.storage.RequestedPalettedExposure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A disposable-camera photo on the photo page, built from the Exposure {@link Frame} the server
 * recorded when the shot landed.
 *
 * <p>Exposure keeps the pixels on the server; {@link ExposureClient#renderedExposures()} requests
 * them and applies the film's look. Once that image exists it is copied once into a texture of our
 * own, so the page can blit it exactly like a ride photo and {@link #save()} can write the same
 * pixels. Until then the photo reports {@link Status#DEVELOPING}; if the server has no such
 * exposure it reports {@link Status#MISSING}.</p>
 */
public final class CameraDeathPhoto implements DeathPhoto {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicInteger NEXT_TEXTURE = new AtomicInteger();

    private final Frame frame;
    private DynamicTexture texture;
    private ResourceLocation textureId;
    private boolean missing;

    public CameraDeathPhoto(Frame frame) {
        this.frame = frame;
    }

    @Override
    public Status status() {
        if (texture != null) return Status.READY;
        if (missing) return Status.MISSING;
        try {
            return develop();
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Could not load camera photo {}", frame.identifier(), e);
            missing = true;
            return Status.MISSING;
        }
    }

    /** Poll Exposure for the image and, once it exists, copy it into our own texture. */
    private Status develop() {
        if (frame.identifier().isId()) {
            RequestedPalettedExposure requested =
                    ExposureClient.exposureStore().getOrRequest(frame.identifier().id());
            if (requested.isError()) {
                missing = true;
                return Status.MISSING;
            }
            if (requested.getData().isEmpty()) return Status.DEVELOPING;
        }
        RenderableImage image = ExposureClient.renderedExposures().getOrCreate(frame);
        if (image == RenderableImage.MISSING) {
            missing = true;
            return Status.MISSING;
        }
        if (image.isEmpty() || image.width() <= 0 || image.height() <= 0) return Status.DEVELOPING;
        upload(image);
        return Status.READY;
    }

    private void upload(RenderableImage image) {
        int w = image.width(), h = image.height();
        NativeImage pixels = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                pixels.setPixelRGBA(x, y, argbToAbgr(image.getPixelARGB(x, y)));
            }
        }
        texture = new DynamicTexture(pixels);
        textureId = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID,
                "death_photo/camera_" + NEXT_TEXTURE.incrementAndGet());
        Minecraft.getInstance().getTextureManager().register(textureId, texture);
    }

    /** Exposure hands out ARGB; {@link NativeImage} stores ABGR. Forced opaque — a photo has no holes. */
    static int argbToAbgr(int argb) {
        int r = (argb >> 16) & 0xFF;
        int b = argb & 0xFF;
        return 0xFF000000 | (b << 16) | (argb & 0x0000FF00) | r;
    }

    @Override
    public ResourceLocation texture() {
        return textureId;
    }

    @Override
    public int width() {
        return texture != null ? texture.getPixels().getWidth() : 1;
    }

    @Override
    public int height() {
        return texture != null ? texture.getPixels().getHeight() : 1;
    }

    @Override
    public Path save() throws IOException {
        if (texture == null || texture.getPixels() == null) {
            throw new IOException("camera photo " + frame.identifier() + " is not developed yet");
        }
        Path target = CameraPhotoExporter.uniquePath();
        texture.getPixels().writeToFile(target);
        LOGGER.debug("[DungeonTrain] Saved camera photo {} -> {}", frame.identifier(), target);
        return target;
    }

    @Override
    public void release() {
        if (textureId != null) {
            Minecraft.getInstance().getTextureManager().release(textureId); // closes the texture
        }
        texture = null;
        textureId = null;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "CameraDeathPhoto[%s]", frame.identifier());
    }
}
