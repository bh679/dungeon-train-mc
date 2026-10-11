package games.brennan.dungeontrain.client.live;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Copies the finished frame out of the main render target into a small buffer for the encoder.
 *
 * <p>Render thread only. Each grab blits the main target (what the player sees, HUD included —
 * whatever Iris/Sodium/Veil drew into it) down to a {@code width×height} target and reads that
 * back into a pooled direct buffer. The read is synchronous; at 720p it is a millisecond or two.</p>
 *
 * <p>Pixels are RGBA, bottom-up (GL order); the encoder's {@code -vf vflip} turns them over.
 * {@link #latest()} hands out the newest buffer and nulls it — a frame the encoder did not take
 * before the next grab is simply overwritten.</p>
 */
public final class LiveFrameGrabber implements AutoCloseable {

    private static final int POOL = 3;

    private final int width, height, frameBytes;
    private final TextureTarget target;
    private final ArrayBlockingQueue<ByteBuffer> pool = new ArrayBlockingQueue<>(POOL);
    private final AtomicReference<ByteBuffer> latest = new AtomicReference<>();
    private boolean closed;

    public LiveFrameGrabber(int width, int height) {
        this.width = width;
        this.height = height;
        this.frameBytes = width * height * 4;
        this.target = new TextureTarget(width, height, false, Minecraft.ON_OSX);
        for (int i = 0; i < POOL; i++) pool.add(MemoryUtil.memAlloc(frameBytes));
    }

    public int width() { return width; }
    public int height() { return height; }

    /** Render thread: blit the finished frame down, then read the small target back. */
    public void grab(RenderTarget main) {
        if (closed || main == null) return;
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, target.frameBufferId);
        GL30C.glBlitFramebuffer(0, 0, main.width, main.height, 0, 0, width, height,
            GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_LINEAR);

        ByteBuffer out = pool.poll();
        if (out != null) {
            // Synchronous read of a 720p RGBA target: ~1-2 ms on a desktop GPU. A PBO ping-pong was
            // tried first and SIGBUSed inside memCopy on Apple Silicon (mapped pixel-pack memory is
            // not plain readable there), so the simple path stays.
            GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, target.frameBufferId);
            GlStateManager._pixelStore(GL11C.GL_PACK_ALIGNMENT, 4);
            out.clear();
            GL11C.glReadPixels(0, 0, width, height, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, out);
            out.position(0).limit(frameBytes);
            ByteBuffer dropped = latest.getAndSet(out);
            if (dropped != null) pool.offer(dropped);
        }
        main.bindWrite(true);
    }

    /** Any thread: the newest frame not yet taken, or null. Return it with {@link #recycle}. */
    @Nullable
    public ByteBuffer latest() {
        return latest.getAndSet(null);
    }

    public void recycle(ByteBuffer buf) {
        if (buf != null && !closed) pool.offer(buf);
    }

    /** Render thread. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        target.destroyBuffers();
        ByteBuffer l = latest.getAndSet(null);
        if (l != null) MemoryUtil.memFree(l);
        ByteBuffer b;
        while ((b = pool.poll()) != null) MemoryUtil.memFree(b);
    }
}
