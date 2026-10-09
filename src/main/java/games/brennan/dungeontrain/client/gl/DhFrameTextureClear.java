package games.brennan.dungeontrain.client.gl;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

/**
 * Clears a set of frame textures (depth to the far plane, colour to transparent black) through a
 * scratch framebuffer, restoring every piece of GL state it touches. The GL half of
 * {@code client/DistantHorizonsFrameClear}: that class decides <em>which</em> textures (Distant
 * Horizons' own and Iris's copy) and why; this one only knows how to empty them. Names no DH or
 * Iris type, so it loads anywhere — including the unit test that records its call sequence.
 */
public final class DhFrameTextureClear {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int NO_TEXTURE = 0;
    private static final int NO_FRAMEBUFFER = 0;
    private static final double FAR_DEPTH = 1.0;

    private static int scratchFramebuffer = NO_FRAMEBUFFER;
    private static boolean incompleteLogged;

    private DhFrameTextureClear() {}

    /** The real LWJGL-backed {@link GlOps}. */
    public static GlOps lwjgl() {
        return LwjglOps.INSTANCE;
    }

    /**
     * The testable core: clears {@code depthTex} to the far plane and {@code colorTex} to transparent
     * black, then {@code irisDepthTex} (when non-zero) to the far plane, restoring every piece of GL
     * state it touched. A zero DH depth texture means DH has not rendered yet — nothing to clear.
     */
    public static void clear(GlOps gl, int depthTex, int colorTex, int irisDepthTex) {
        if (depthTex == NO_TEXTURE) return;

        int previousFramebuffer = gl.drawFramebufferBinding();
        boolean previousDepthMask = gl.depthMask();
        boolean[] previousColorMask = gl.colorMask();
        boolean previousScissor = gl.scissorEnabled();

        gl.depthMask(true);
        gl.colorMask(true, true, true, true);
        if (previousScissor) gl.scissor(false);
        try {
            int fbo = gl.scratchFramebuffer();
            gl.bindDrawFramebuffer(fbo);
            gl.attachDepth(depthTex);
            gl.attachColor(colorTex);
            if (gl.framebufferComplete()) {
                gl.clearDepth(FAR_DEPTH);
                gl.clearColor(0f, 0f, 0f, 0f);
                gl.clear(colorTex == NO_TEXTURE
                        ? GL11.GL_DEPTH_BUFFER_BIT
                        : GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT);
            } else {
                gl.logIncomplete("DH frame");
            }
            if (irisDepthTex != NO_TEXTURE) {
                gl.attachDepth(irisDepthTex);
                gl.attachColor(NO_TEXTURE);
                if (gl.framebufferComplete()) {
                    gl.clearDepth(FAR_DEPTH);
                    gl.clear(GL11.GL_DEPTH_BUFFER_BIT);
                } else {
                    gl.logIncomplete("Iris no-translucent depth");
                }
            }
            // Detach so the scratch FBO never keeps a texture alive past its owner's lifetime.
            gl.attachDepth(NO_TEXTURE);
            gl.attachColor(NO_TEXTURE);
        } finally {
            gl.bindDrawFramebuffer(previousFramebuffer);
            if (previousScissor) gl.scissor(true);
            gl.colorMask(previousColorMask[0], previousColorMask[1], previousColorMask[2], previousColorMask[3]);
            gl.depthMask(previousDepthMask);
        }
    }

    /** The GL calls {@link #clear} needs, so the sequence can be recorded in a test. */
    public interface GlOps {
        int drawFramebufferBinding();
        boolean depthMask();
        boolean[] colorMask();
        boolean scissorEnabled();
        void depthMask(boolean enabled);
        void colorMask(boolean r, boolean g, boolean b, boolean a);
        void scissor(boolean enabled);
        int scratchFramebuffer();
        void bindDrawFramebuffer(int fbo);
        void attachDepth(int texture);
        void attachColor(int texture);
        boolean framebufferComplete();
        void clearDepth(double depth);
        void clearColor(float r, float g, float b, float a);
        void clear(int mask);
        void logIncomplete(String what);
    }

    private enum LwjglOps implements GlOps {
        INSTANCE;

        @Override public int drawFramebufferBinding() { return SafeGlGet.getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING); }
        @Override public boolean depthMask() { return GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK); }
        @Override public boolean scissorEnabled() { return GL11.glIsEnabled(GL11.GL_SCISSOR_TEST); }

        @Override
        public boolean[] colorMask() {
            boolean[] mask = new boolean[4];
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                java.nio.ByteBuffer values = stack.malloc(4);
                GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, values);
                for (int i = 0; i < 4; i++) mask[i] = values.get(i) != 0;
            }
            return mask;
        }

        @Override public void depthMask(boolean enabled) { GlStateManager._depthMask(enabled); }
        @Override public void colorMask(boolean r, boolean g, boolean b, boolean a) { GlStateManager._colorMask(r, g, b, a); }

        @Override
        public void scissor(boolean enabled) {
            if (enabled) GlStateManager._enableScissorTest(); else GlStateManager._disableScissorTest();
        }

        @Override
        public int scratchFramebuffer() {
            if (scratchFramebuffer == NO_FRAMEBUFFER) scratchFramebuffer = GL30.glGenFramebuffers();
            return scratchFramebuffer;
        }

        @Override public void bindDrawFramebuffer(int fbo) { GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo); }

        @Override
        public void attachDepth(int texture) {
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, texture, 0);
        }

        @Override
        public void attachColor(int texture) {
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        }

        @Override
        public boolean framebufferComplete() {
            return GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
        }

        @Override public void clearDepth(double depth) { GlStateManager._clearDepth(depth); }
        @Override public void clearColor(float r, float g, float b, float a) { GlStateManager._clearColor(r, g, b, a); }
        @Override public void clear(int mask) { GlStateManager._clear(mask, false); }

        @Override
        public void logIncomplete(String what) {
            if (incompleteLogged) return;
            incompleteLogged = true;
            LOGGER.debug("[DungeonTrain] Could not clear the {} texture on a suppressed Distant Horizons "
                    + "frame: scratch framebuffer incomplete", what);
        }
    }
}
