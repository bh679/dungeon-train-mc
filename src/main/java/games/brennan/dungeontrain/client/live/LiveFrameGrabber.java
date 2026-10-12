package games.brennan.dungeontrain.client.live;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Copies the finished frame out of the main render target into a small buffer for the encoder.
 *
 * <p>Render thread only. Each grab blits the main target (what the player sees, HUD included —
 * whatever Iris/Sodium/Veil drew into it) down to a {@code width×height} target and starts an
 * asynchronous read of it into one of a ring of pixel-pack buffers, guarded by a fence. The next
 * grab copies the newest finished read into a pooled direct buffer with {@code glGetBufferSubData},
 * so a frame reaches the encoder one grab late (~50 ms at 20 fps) and the render thread never waits
 * for the GPU. A synchronous {@code glReadPixels} made the driver finish the whole queued frame on
 * every grab, which on a GPU-bound machine cost the rest of its frame time. The buffers are never
 * mapped: mapped pixel-pack memory SIGBUSed inside memCopy on Apple Silicon.</p>
 *
 * <p>Pixels are RGBA, bottom-up (GL order); the encoder's {@code -vf vflip} turns them over.
 * {@link #latest()} hands out the newest buffer and nulls it — a frame the encoder did not take
 * before the next grab is simply overwritten.</p>
 */
public final class LiveFrameGrabber implements AutoCloseable {

    private static final int POOL = 3;
    private static final int RING = 3;

    private final int width, height, frameBytes;
    private final TextureTarget target;
    private final ArrayBlockingQueue<ByteBuffer> pool = new ArrayBlockingQueue<>(POOL);
    private final AtomicReference<ByteBuffer> latest = new AtomicReference<>();
    private final LivePboRing ring = new LivePboRing(RING);
    private final int[] pbos = new int[RING];
    private final long[] fences = new long[RING];
    private boolean closed;

    public LiveFrameGrabber(int width, int height) {
        this.width = width;
        this.height = height;
        this.frameBytes = width * height * 4;
        this.target = new TextureTarget(width, height, false, Minecraft.ON_OSX);
        for (int i = 0; i < POOL; i++) pool.add(MemoryUtil.memAlloc(frameBytes));
        GL15C.glGenBuffers(pbos);
        for (int pbo : pbos) {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, pbo);
            GL15C.glBufferData(GL21C.GL_PIXEL_PACK_BUFFER, frameBytes, GL15C.GL_STREAM_READ);
        }
        GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
    }

    public int width() { return width; }
    public int height() { return height; }

    /** Render thread: hand the newest finished read to the encoder, then start reading this frame. */
    public void grab(RenderTarget main) {
        if (closed || main == null) return;
        long startNs = System.nanoTime();
        try {
            collectFinished();
            GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, main.frameBufferId);
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, target.frameBufferId);
            GL30C.glBlitFramebuffer(0, 0, main.width, main.height, 0, 0, width, height,
                GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_LINEAR);
            StreamNameTag.drawInto(target); // viewers' copy only — the main target is untouched
            int slot = ring.issue();
            if (slot >= 0) {
                // Into the bound pack buffer at offset 0: queued on the GPU, returns at once.
                GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, target.frameBufferId);
                GlStateManager._pixelStore(GL11C.GL_PACK_ALIGNMENT, 4);
                GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, pbos[slot]);
                GL11C.glReadPixels(0, 0, width, height, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, 0L);
                fences[slot] = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            }
            // else: every buffer still waits on the GPU — skip this frame rather than stall.
            else LivePerfStats.grabSkipped();
        } finally {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
            main.bindWrite(true);
            LivePerfStats.grab(System.nanoTime() - startNs);
        }
    }

    /** Frees every read the GPU has finished and publishes the newest of them. */
    private void collectFinished() {
        int newest = ring.collect(this::finished, this::releaseFence);
        if (newest < 0) return;
        ByteBuffer out = pool.poll();
        if (out == null) return;
        GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, pbos[newest]);
        out.clear();
        GL15C.glGetBufferSubData(GL21C.GL_PIXEL_PACK_BUFFER, 0L, out);
        out.position(0).limit(frameBytes);
        ByteBuffer dropped = latest.getAndSet(out);
        if (dropped != null) pool.offer(dropped);
    }

    /** Polls the slot's fence without waiting. A failed wait counts as done so a slot can't wedge. */
    private boolean finished(int slot) {
        int r = GL32C.glClientWaitSync(fences[slot], GL32C.GL_SYNC_FLUSH_COMMANDS_BIT, 0L);
        return r == GL32C.GL_ALREADY_SIGNALED || r == GL32C.GL_CONDITION_SATISFIED || r == GL32C.GL_WAIT_FAILED;
    }

    private void releaseFence(int slot) {
        if (fences[slot] != 0L) GL32C.glDeleteSync(fences[slot]);
        fences[slot] = 0L;
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
        ring.drain(this::releaseFence);
        GL15C.glDeleteBuffers(pbos);
        target.destroyBuffers();
        ByteBuffer l = latest.getAndSet(null);
        if (l != null) MemoryUtil.memFree(l);
        ByteBuffer b;
        while ((b = pool.poll()) != null) MemoryUtil.memFree(b);
    }
}
