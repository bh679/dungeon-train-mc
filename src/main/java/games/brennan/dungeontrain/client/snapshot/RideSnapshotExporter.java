package games.brennan.dungeontrain.client.snapshot;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Writes in-memory ride photos ({@link RideSnapshot}) to disk as PNGs in the
 * game's {@code screenshots/} folder.
 *
 * <p>The pixels are recovered from the live {@link DynamicTexture} the gallery
 * owns — its CPU-side {@link NativeImage} is retained until the texture is
 * released ({@link RideSnapshotGallery}) — so nothing in the capture path or the
 * {@link RideSnapshot} record needs to change. Saved images are therefore the
 * stored down-scaled (640px long-edge) frame, matching what the death screen
 * shows as a background.</p>
 *
 * <p>Called on the client main thread (a handful of small PNGs — a synchronous
 * write is fine).</p>
 */
public final class RideSnapshotExporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RideSnapshotExporter() {}

    /** The {@code <gameDir>/screenshots} directory (vanilla's screenshot folder). */
    public static Path screenshotsDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(Screenshot.SCREENSHOT_DIR);
    }

    /**
     * Write {@code shot} to a uniquely-named PNG under {@link #screenshotsDir()}
     * and return the saved path. An already-offloaded photo ({@link RideSnapshot#diskPath()})
     * is copied straight from its disk cache; otherwise its pixels are read from the live texture.
     *
     * @throws IOException if the write/copy fails, or the photo is neither cached nor has live
     *                     texture pixels (released without an offload)
     */
    public static Path save(RideSnapshot shot) throws IOException {
        Path dir = screenshotsDir();
        Files.createDirectories(dir);
        Path target = uniquePath(dir, shot);

        // If the photo was already offloaded to the disk cache (its texture freed to save memory),
        // copy that PNG straight across — no texture read-back or re-encode, and it works even
        // though the live texture is gone. The cache holds the same down-scaled frame.
        Path cached = shot.diskPath();
        if (cached != null && Files.exists(cached)) {
            Files.copy(cached, target);
            LOGGER.debug("[DungeonTrain] Saved ride photo (from cache) {} -> {}", cached, target);
            return target;
        }

        NativeImage image = pixelsOf(shot);
        if (image == null) {
            throw new IOException("ride photo pixels unavailable (texture released)");
        }
        image.writeToFile(target);
        LOGGER.debug("[DungeonTrain] Saved ride photo {} -> {}", shot.texture(), target);
        return target;
    }

    /** Resolve the gallery's live {@link DynamicTexture} for {@code shot} and read its pixels. */
    private static NativeImage pixelsOf(RideSnapshot shot) {
        AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(shot.texture());
        return (tex instanceof DynamicTexture dyn) ? dyn.getPixels() : null;
    }

    /** A {@code dungeontrain-<datetime>[-tag][_n].png} path that does not yet exist. */
    private static Path uniquePath(Path dir, RideSnapshot shot) {
        String tag = shot.tag() != null ? "-" + shot.tag().name().toLowerCase(Locale.ROOT) : "";
        String base = "dungeontrain-" + Util.getFilenameFormattedDateTime() + tag;
        Path candidate = dir.resolve(base + ".png");
        for (int n = 1; Files.exists(candidate); n++) {
            candidate = dir.resolve(base + "_" + n + ".png");
        }
        return candidate;
    }
}
