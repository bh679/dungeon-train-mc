package games.brennan.dungeontrain.client.deathphotos;

import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.file.Path;

/**
 * One photo on the death screen's photo page: a ride snapshot ({@link RideDeathPhoto}) or a
 * disposable-camera shot ({@link CameraDeathPhoto}). Both end up as a GUI texture the page blits
 * contain-fit, so the strip and the fullscreen viewer treat them alike.
 */
public sealed interface DeathPhoto permits RideDeathPhoto, CameraDeathPhoto {

    /** Whether the photo can be drawn yet. */
    enum Status { READY, DEVELOPING, MISSING }

    /** Current status; a camera photo is {@link Status#DEVELOPING} until its pixels arrive from the server. */
    Status status();

    /** The texture to blit — only meaningful while {@link #status()} is {@link Status#READY}. */
    ResourceLocation texture();

    /** Pixel width of {@link #texture()} (the full blit source). */
    int width();

    /** Pixel height of {@link #texture()}. */
    int height();

    /** Width over height; a square guess until the photo is ready. */
    default float aspect() {
        return status() == Status.READY && height() > 0 ? (float) width() / height() : 1.0f;
    }

    /** Write the photo as a PNG into the game's {@code screenshots/} folder and return the path. */
    Path save() throws IOException;

    /** Free any texture this photo created itself (not ones owned by the ride gallery). */
    void release();
}
