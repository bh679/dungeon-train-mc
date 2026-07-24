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
    /** Quality ladder the size-bounded encode steps down before it resorts to shrinking the image. */
    private static final float[] QUALITY_LADDER = {0.85f, 0.70f, 0.55f};
    /** Never shrink a bounded shot below this long edge chasing a byte budget — keep it recognisable. */
    private static final int MIN_BOUNDED_EDGE = 480;

    private SnapshotJpegEncoder() {}

    /** JPEG-encode {@code img} at {@link #QUALITY}. Returns {@code null} on encode failure. */
    static byte[] encode(NativeImage img) {
        return encodeAt(img, QUALITY);
    }

    /**
     * JPEG-encode {@code img} so the result fits within {@code maxBytes}: step the quality down
     * {@link #QUALITY_LADDER}, then (if a hi-res shot is still over budget) shrink the image ~30% at a
     * time and retry, down to {@link #MIN_BOUNDED_EDGE}. Returns the smallest encoding produced — always
     * under budget in practice — or {@code null} on encode failure. Base-1080 shots clear the first rung
     * immediately, so their behaviour is unchanged.
     */
    static byte[] encode(NativeImage img, int maxBytes) {
        byte[] smallest = null;
        NativeImage work = img;
        boolean ownsWork = false;
        try {
            for (int shrink = 0; shrink < 6; shrink++) {
                for (float q : QUALITY_LADDER) {
                    byte[] out = encodeAt(work, q);
                    if (out == null) return smallest;
                    if (out.length <= maxBytes) return out;
                    if (smallest == null || out.length < smallest.length) smallest = out;
                }
                int longEdge = Math.max(work.getWidth(), work.getHeight());
                int next = (int) Math.floor(longEdge * 0.7);
                if (next < MIN_BOUNDED_EDGE || next >= longEdge) break; // can't (or shouldn't) shrink further
                NativeImage smaller = downscaleLongEdge(work, next);
                if (ownsWork) work.close();
                work = smaller;
                ownsWork = true;
            }
            return smallest; // best effort; the quality/shrink ladder keeps this well under real budgets
        } finally {
            if (ownsWork && work != img) work.close();
        }
    }

    /** Encode {@code img} at an explicit JPEG quality. Returns {@code null} on failure. */
    private static byte[] encodeAt(NativeImage img, float quality) {
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
                params.setCompressionQuality(quality);

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

    /** Nearest-neighbour copy of {@code src} scaled so its long edge is {@code maxEdge} (caller owns the result). */
    private static NativeImage downscaleLongEdge(NativeImage src, int maxEdge) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        int dw, dh;
        if (sw >= sh) {
            dw = Math.min(maxEdge, sw);
            dh = Math.max(1, Math.round(dw * (float) sh / sw));
        } else {
            dh = Math.min(maxEdge, sh);
            dw = Math.max(1, Math.round(dh * (float) sw / sh));
        }
        NativeImage dst = new NativeImage(dw, dh, false);
        for (int y = 0; y < dh; y++) {
            int sy = y * sh / dh;
            for (int x = 0; x < dw; x++) {
                dst.setPixelRGBA(x, y, src.getPixelRGBA(x * sw / dw, sy));
            }
        }
        return dst;
    }
}
