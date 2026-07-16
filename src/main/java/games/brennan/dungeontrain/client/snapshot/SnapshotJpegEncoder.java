package games.brennan.dungeontrain.client.snapshot;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.util.FastColor;
import org.slf4j.Logger;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

/**
 * Re-encodes a captured {@link NativeImage} as JPEG for network transmission. Ride photos are opaque
 * world renders, so the lossy compression (and dropped alpha) is invisible in practice while shrinking
 * the payload well below what lossless PNG achieves on this kind of high-entropy 3D content — the
 * margin {@link games.brennan.dungeontrain.net.DeathPhotoPacket}'s 1&nbsp;MB cap needs at 1080p.
 *
 * <p>Local gallery/disk storage stays PNG (via {@link NativeImage#asByteArray()} /
 * {@link RideSnapshotDisk}) — only the network-bound copy goes through here.</p>
 */
final class SnapshotJpegEncoder {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final float QUALITY = 0.85f;

    private SnapshotJpegEncoder() {}

    /** JPEG-encode {@code img} at {@link #QUALITY}. Returns {@code null} on encode failure. */
    static byte[] encode(NativeImage img) {
        try {
            int w = img.getWidth();
            int h = img.getHeight();
            BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    // NativeImage packs pixels as ABGR32; re-pack as ARGB32 (full alpha) for BufferedImage.
                    int abgr = img.getPixelRGBA(x, y);
                    int argb = FastColor.ARGB32.color(
                            FastColor.ABGR32.red(abgr), FastColor.ABGR32.green(abgr), FastColor.ABGR32.blue(abgr));
                    buffered.setRGB(x, y, argb);
                }
            }

            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
            if (!writers.hasNext()) return null;
            ImageWriter writer = writers.next();
            try {
                ImageWriteParam params = writer.getDefaultWriteParam();
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(QUALITY);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(baos)) {
                    writer.setOutput(ios);
                    writer.write(null, new IIOImage(buffered, null, null), params);
                }
                return baos.toByteArray();
            } finally {
                writer.dispose();
            }
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Ride snapshot JPEG encode failed", e);
            return null;
        }
    }
}
