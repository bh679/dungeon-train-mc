package games.brennan.dungeontrain.client.gl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

/**
 * {@code glGetInteger} that cannot write past its buffer.
 *
 * <p>LWJGL's {@code GL11.glGetInteger(pname)} reserves exactly one int on the thread's
 * {@link MemoryStack}. Some queries return more than one value — Apple's GL-over-Metal driver answers
 * {@code GL_POLYGON_MODE} with two ints (front and back, {@code 0x1B02 0x1B02}). On an empty stack
 * frame that single int is the last 4 bytes of the stack's 64 KB malloc'd buffer, so the second int
 * lands on the <em>next</em> heap block and clobbers the low half of its first word. That turned into
 * random native crashes far from the cause: C2 compiler-arena {@code Chunk::chop} SIGBUS and Metal
 * command-buffer {@code objc_msgSend} SIGSEGV, every fault address ending in {@code 00001b02}.
 *
 * <p>Distant Horizons makes that query every time it saves GL state, several times a frame; see
 * {@code DhGlStateSaveMixin}. Four slots cover every multi-value integer state DH reads this way.</p>
 */
public final class SafeGlGet {

    /** Room for the widest answer a single-value-looking {@code glGet} query can give. */
    private static final int SLOTS = 4;

    private SafeGlGet() {}

    public static int getInteger(int pname) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer values = stack.mallocInt(SLOTS);
            GL11.glGetIntegerv(pname, values);
            return values.get(0);
        }
    }
}
