package games.brennan.dungeontrain.client.logo;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The ring logo's crack depth map on the GPU: a single-channel, mipmapped texture fed by
 * {@link LogoCracks}. The field is built and regrown off the render thread (a segment's regrowth
 * carves a 768x1024 block), and only the rows a regrowth touched are re-uploaded.
 */
final class LogoCrackTexture {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DungeonTrain logo cracks");
        t.setDaemon(true);
        return t;
    });
    private final ReentrantLock lock = new ReentrantLock();
    private final CompletableFuture<LogoCracks> field;
    private int textureId = -1;
    private ByteBuffer staging;
    private boolean failed;

    LogoCrackTexture() {
        field = CompletableFuture.supplyAsync(() -> {
            lock.lock();
            try {
                return new LogoCracks(LogoRingGeometry.PIECES, LogoRingGeometry.segmentTexels(), LogoCracks.Settings.LOGO);
            } finally {
                lock.unlock();
            }
        }, worker);
    }

    /** The GL texture id once the field is built and uploaded, else -1. Render thread only. */
    int textureId() {
        if (failed || !field.isDone()) return -1;
        LogoCracks f = field.getNow(null);
        if (f == null) {
            failed = true;
            LOGGER.warn("[DungeonTrain] logo ring cracks failed to build; the logo is off", field.handle((v, e) -> e).join());
            return -1;
        }
        if (textureId < 0) create(f);
        else uploadDirty(f);
        return textureId;
    }

    /** Regrow a segment's hidden faces in the background (bit k = face k hidden). */
    void regrowHidden(int piece, int hiddenFaces) {
        if (!field.isDone() || failed) return;
        worker.execute(() -> {
            lock.lock();
            try {
                field.join().regrowHidden(piece, hiddenFaces);
            } finally {
                lock.unlock();
            }
        });
    }

    private void create(LogoCracks f) {
        RenderSystem.assertOnRenderThread();
        staging = MemoryUtil.memAlloc(f.width() * f.height());
        lock.lock();
        try {
            staging.put(f.data()).flip();
            f.takeDirty();
        } finally {
            lock.unlock();
        }
        textureId = GlStateManager._genTexture();
        RenderSystem.bindTexture(textureId);
        resetUnpack();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, f.width(), f.height(), 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, staging);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
    }

    /** Re-upload the segments a regrowth recut; skipped this frame while one is still carving. */
    private void uploadDirty(LogoCracks f) {
        if (!lock.tryLock()) return;
        boolean any = false;
        try {
            boolean[] dirty = f.takeDirty();
            for (int piece = 0; piece < dirty.length; piece++) {
                if (!dirty[piece]) continue;
                int top = piece * LogoCracks.BLOCK, start = top * f.width(), len = LogoCracks.BLOCK * f.width();
                staging.clear();
                staging.put(start, f.data(), start, len);
                if (!any) { RenderSystem.bindTexture(textureId); resetUnpack(); any = true; }
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, top, f.width(), LogoCracks.BLOCK, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE,
                        MemoryUtil.memAddress(staging) + start);
            }
        } finally {
            lock.unlock();
        }
        if (any) GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
    }

    /** Minecraft leaves row length / skips / alignment set from its own uploads. */
    private static void resetUnpack() {
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
    }

    void close() {
        if (textureId >= 0) {
            RenderSystem.deleteTexture(textureId);
            textureId = -1;
        }
        if (staging != null) {
            MemoryUtil.memFree(staging);
            staging = null;
        }
        worker.shutdownNow();
    }
}
