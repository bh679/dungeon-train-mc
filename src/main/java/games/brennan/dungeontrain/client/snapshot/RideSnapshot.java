package games.brennan.dungeontrain.client.snapshot;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * One captured third-person ride photo for the death screen.
 *
 * <p>Identity-keyed: no {@code equals}/{@code hashCode} override, so every capture is a distinct
 * object — matching the {@code IdentityHashMap}/{@code ==} that {@link DeathBackgroundAssigner}
 * relies on. The descriptive fields ({@code tag}, {@code width}, {@code height},
 * {@code createdTick}) are immutable.</p>
 *
 * <p>Only the <b>backing resource handle</b> is mutable (the texture lifecycle was already
 * mutable, owned by {@link RideSnapshotGallery}): a shot is either</p>
 * <ul>
 *   <li><b>in memory</b> — holds the live {@link DynamicTexture} and its registered
 *       {@link ResourceLocation}; or</li>
 *   <li><b>on disk</b> — holds a {@link Path}; its texture has been released to free VRAM/RAM.
 *       {@link #texture()} lazily reloads it from disk on demand (at the death screen) and caches
 *       the result.</li>
 * </ul>
 */
public final class RideSnapshot {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final SnapshotTag tag;
    private final SnapshotMeta meta;
    private final int width;
    private final int height;
    private final long createdTick;

    // ── mutable backing (single client thread; see RideSnapshotGallery) ──
    private DynamicTexture liveTexture;   // non-null while in memory (or after a lazy disk reload)
    private ResourceLocation textureId;   // registered id for liveTexture; null once released without a reload
    private Path diskPath;                 // non-null once flushed to disk
    private boolean loadFailed;            // a disk reload that failed — don't retry every frame

    /** Live (in-memory) capture: keeps the {@link DynamicTexture} so its pixels can be flushed later. */
    public RideSnapshot(DynamicTexture texture, ResourceLocation textureId,
                        SnapshotTag tag, SnapshotMeta meta, int width, int height, long createdTick) {
        this.liveTexture = texture;
        this.textureId = textureId;
        this.tag = tag;
        this.meta = meta == null ? SnapshotMeta.EMPTY : meta;
        this.width = width;
        this.height = height;
        this.createdTick = createdTick;
    }

    /** Id-only capture (no live texture handle) — used by tests and any caller that won't flush. */
    public RideSnapshot(ResourceLocation textureId, SnapshotTag tag, int width, int height, long createdTick) {
        this(null, textureId, tag, SnapshotMeta.EMPTY, width, height, createdTick);
    }

    public SnapshotTag tag() { return tag; }

    /** Per-photo context (biome/band/difficulty/cart) sampled at capture; never null. */
    public SnapshotMeta meta() { return meta; }
    public int width() { return width; }
    public int height() { return height; }
    public long createdTick() { return createdTick; }

    /** Width / height; falls back to 16:9 if height is degenerate. */
    public float aspect() {
        return height <= 0 ? 16.0f / 9.0f : (float) width / (float) height;
    }

    /** Already offloaded to disk (its in-run texture released)? */
    public boolean isFlushed() {
        return diskPath != null;
    }

    /**
     * The offload cache file backing this photo, or {@code null} if it was never flushed to disk.
     * Lets a consumer (e.g. the gallery's save-to-{@code screenshots/}) copy the existing PNG
     * instead of re-reading the released texture.
     */
    public Path diskPath() {
        return diskPath;
    }

    /** In memory, holding a live texture and not yet flushed — a candidate for offloading. */
    public boolean isInMemoryUnflushed() {
        return diskPath == null && liveTexture != null;
    }

    /**
     * JPEG-encode this photo for sending off the client (e.g. the Discord death report's ride-photo
     * image). Local storage (disk/gallery) stays lossless PNG; this re-encodes to JPEG only for the
     * network hop, since JPEG runs well under the packet's 1&nbsp;MB cap at 1080p where PNG does not.
     * Lazily reloads a released shot via {@link #texture()}. Returns {@code null} when no pixels are
     * available or encoding fails — callers fall back to no/other image. Runs on the client thread,
     * only on the rare "send this run's photo" event (once per death / echo encounter), not per frame.
     */
    public byte[] photoBytes() {
        try {
            NativeImage px;
            if (diskPath != null) {
                px = RideSnapshotDisk.read(diskPath);
                if (px == null) return null;
                try {
                    return SnapshotJpegEncoder.encode(px);
                } finally {
                    px.close();
                }
            }
            if (liveTexture == null) texture(); // lazy reload if the texture was released
            if (liveTexture == null) return null;
            px = liveTexture.getPixels();
            return px == null ? null : SnapshotJpegEncoder.encode(px);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Ride snapshot JPEG encode failed", e);
            return null;
        }
    }

    /**
     * The texture to blit. Returns the live id when in memory; when flushed, lazily reloads the PNG
     * into a fresh {@link DynamicTexture} (caching success <em>and</em> failure so it never retries
     * every frame). Returns {@code null} when there is nothing to draw (load failed, or it never had
     * a texture) — the death screen paints its solid overlay for that page.
     */
    public ResourceLocation texture() {
        if (textureId != null) return textureId;
        if (diskPath == null || loadFailed) return null;
        NativeImage img = RideSnapshotDisk.read(diskPath);
        if (img == null) {
            loadFailed = true;
            return null;
        }
        try {
            DynamicTexture tex = new DynamicTexture(img);
            ResourceLocation id = Minecraft.getInstance().getTextureManager().register("dungeontrain_ride_disk", tex);
            this.liveTexture = tex;
            this.textureId = id;
            return id;
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Ride snapshot reload failed for {}", diskPath, e);
            img.close();
            loadFailed = true;
            return null;
        }
    }

    /**
     * Offload this photo: write its pixels to disk and release the in-memory texture (free VRAM/RAM).
     * No-op unless it is an in-memory unflushed shot with readable pixels. Returns {@code true} once
     * it is on disk (already-flushed counts as success; a write failure leaves it in memory to retry).
     */
    public boolean flush() {
        if (isFlushed()) return true;
        if (liveTexture == null) return false;
        NativeImage px = liveTexture.getPixels(); // live image owned by the texture (closed on release)
        if (px == null) {
            // Pixels already gone (double-release) — just drop the dead handle.
            releaseTexture();
            return false;
        }
        Path path = RideSnapshotDisk.write(px); // must run before releaseTexture() closes px
        if (path == null) return false;          // write failed — keep it in memory, retry next window
        this.diskPath = path;
        releaseTexture();                        // frees VRAM; texture() reloads from disk on demand
        return true;
    }

    /** Release the live GPU texture (memory only); keeps any disk file so {@link #texture()} can reload. */
    public void releaseTexture() {
        if (textureId != null) {
            try {
                Minecraft.getInstance().getTextureManager().release(textureId);
            } catch (Exception e) {
                LOGGER.warn("[DungeonTrain] Failed releasing ride snapshot texture {}", textureId, e);
            }
        }
        liveTexture = null;
        textureId = null;
    }

    /** Fully discard (eviction beyond a cap): release the texture and delete any disk file. */
    public void discard() {
        releaseTexture();
        if (diskPath != null) {
            RideSnapshotDisk.delete(diskPath);
            diskPath = null;
        }
        loadFailed = false;
    }
}
