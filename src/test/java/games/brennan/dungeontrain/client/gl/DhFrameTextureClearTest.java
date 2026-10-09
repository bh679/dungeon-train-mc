package games.brennan.dungeontrain.client.gl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The save/clear/restore sequence {@link DhFrameTextureClear#clear} runs on a suppressed
 * Distant Horizons frame, against a recording fake — no GL context, no Minecraft bootstrap.
 */
final class DhFrameTextureClearTest {

    private static final int DH_DEPTH = 11;
    private static final int DH_COLOR = 12;
    private static final int IRIS_DEPTH = 13;
    private static final int NONE = 0;
    private static final int PREVIOUS_FBO = 7;
    private static final int SCRATCH_FBO = 99;
    private static final int DEPTH_AND_COLOR = GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT;

    @Test
    @DisplayName("DH has not rendered yet (depth texture 0) → nothing is touched")
    void noDepthTexture_noGlCalls() {
        RecordingOps gl = new RecordingOps(true);
        assertFalse(DhFrameTextureClear.clear(gl, NONE, DH_COLOR, IRIS_DEPTH));
        assertTrue(gl.calls.isEmpty(), gl.calls.toString());
    }

    @Test
    @DisplayName("both DH textures cleared, then Iris's copy, with every state bit restored")
    void fullClear_restoresState() {
        RecordingOps gl = new RecordingOps(true);
        gl.depthMask = false;
        gl.colorMask = new boolean[] {true, false, true, false};
        gl.scissor = true;

        assertTrue(DhFrameTextureClear.clear(gl, DH_DEPTH, DH_COLOR, IRIS_DEPTH));

        assertEquals(List.of(
                "depthMask(true)", "colorMask(true,true,true,true)", "scissor(false)",
                "bind(" + SCRATCH_FBO + ")", "depth(" + DH_DEPTH + ")", "color(" + DH_COLOR + ")",
                "clearDepth(1.0)", "clearColor", "clear(" + DEPTH_AND_COLOR + ")",
                "depth(" + IRIS_DEPTH + ")", "color(0)", "clearDepth(1.0)", "clear(" + GL11.GL_DEPTH_BUFFER_BIT + ")",
                "depth(0)", "color(0)",
                "bind(" + PREVIOUS_FBO + ")", "scissor(true)", "colorMask(true,false,true,false)", "depthMask(false)"
        ), gl.calls);
    }

    @Test
    @DisplayName("no Iris texture and no DH colour texture → depth-only clear, scissor left alone when it was off")
    void depthOnly_noIris() {
        RecordingOps gl = new RecordingOps(true);
        gl.scissor = false;

        DhFrameTextureClear.clear(gl, DH_DEPTH, NONE, NONE);

        assertTrue(gl.calls.contains("clear(" + GL11.GL_DEPTH_BUFFER_BIT + ")"), gl.calls.toString());
        assertFalse(gl.calls.contains("clear(" + DEPTH_AND_COLOR + ")"), gl.calls.toString());
        assertEquals(1, gl.calls.stream().filter(c -> c.startsWith("clearDepth")).count(), gl.calls.toString());
        assertFalse(gl.calls.stream().anyMatch(c -> c.startsWith("scissor")), gl.calls.toString());
    }

    @Test
    @DisplayName("incomplete scratch framebuffer → no clear, logged, state still restored")
    void incompleteFramebuffer_noClearButRestored() {
        RecordingOps gl = new RecordingOps(false);

        assertFalse(DhFrameTextureClear.clear(gl, DH_DEPTH, DH_COLOR, IRIS_DEPTH));

        assertFalse(gl.calls.stream().anyMatch(c -> c.startsWith("clear(")), gl.calls.toString());
        assertEquals(2, gl.calls.stream().filter(c -> c.startsWith("incomplete")).count(), gl.calls.toString());
        assertEquals("depthMask(true)", gl.calls.get(gl.calls.size() - 1));
        assertTrue(gl.calls.contains("bind(" + PREVIOUS_FBO + ")"), gl.calls.toString());
    }

    /** Records every call in order; the state getters answer with the configured prior state. */
    private static final class RecordingOps implements DhFrameTextureClear.GlOps {
        final List<String> calls = new ArrayList<>();
        boolean depthMask = true;
        boolean[] colorMask = {true, true, true, true};
        boolean scissor = false;
        private final boolean complete;

        RecordingOps(boolean complete) { this.complete = complete; }

        @Override public int drawFramebufferBinding() { return PREVIOUS_FBO; }
        @Override public boolean depthMask() { return depthMask; }
        @Override public boolean[] colorMask() { return colorMask.clone(); }
        @Override public boolean scissorEnabled() { return scissor; }
        @Override public void depthMask(boolean enabled) { calls.add("depthMask(" + enabled + ")"); }
        @Override public void colorMask(boolean r, boolean g, boolean b, boolean a) {
            calls.add("colorMask(" + r + "," + g + "," + b + "," + a + ")");
        }
        @Override public void scissor(boolean enabled) { calls.add("scissor(" + enabled + ")"); }
        @Override public int scratchFramebuffer() { return SCRATCH_FBO; }
        @Override public void bindDrawFramebuffer(int fbo) { calls.add("bind(" + fbo + ")"); }
        @Override public void attachDepth(int texture) { calls.add("depth(" + texture + ")"); }
        @Override public void attachColor(int texture) { calls.add("color(" + texture + ")"); }
        @Override public boolean framebufferComplete() { return complete; }
        @Override public void clearDepth(double depth) { calls.add("clearDepth(" + depth + ")"); }
        @Override public void clearColor(float r, float g, float b, float a) { calls.add("clearColor"); }
        @Override public void clear(int mask) { calls.add("clear(" + mask + ")"); }
        @Override public void logIncomplete(String what) { calls.add("incomplete:" + what); }
    }
}
