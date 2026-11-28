package games.brennan.dungeontrain.client.constellation;

import com.mojang.blaze3d.systems.RenderSystem;
import games.brennan.dungeontrain.client.ShaderCompat;
import games.brennan.dungeontrain.client.skybox.SkyboxStencil;
import org.lwjgl.opengl.GL11;

/**
 * Which pixels are cloud, for the constellation patch: clouds are sky to it, so they vanish behind it
 * the way the sky does, instead of being cut like blocks.
 *
 * <p>Clouds write depth, so depth alone cannot tell them from blocks. While the patch is open, the cloud
 * pass ({@code LevelRendererConstellationCloudMixin}) stamps {@link #BIT} wherever a cloud fragment is
 * drawn. The bit is DT's to use: skybox variant ids sit in the low four bits and the hole reopen uses
 * {@code 0x80}. Off under a shader pack, where the pack owns the frame.</p>
 */
public final class ConstellationClouds {

    public static final int BIT = 0x40;

    private static boolean marked;

    private ConstellationClouds() {}

    /** Before the clouds: start stamping, if the patch is open. Returns whether it did. */
    public static boolean beginMark() {
        marked = false;
        if (!ConstellationClient.patchOpen() || ShaderCompat.active() || !SkyboxStencil.isAvailable()) return false;
        SkyboxStencil.beginMarkPass(BIT);
        marked = true;
        return true;
    }

    /** After the clouds: stop stamping, and leave the stencil as the next pass expects it. */
    public static void endMark() {
        RenderSystem.stencilMask(0xFF);
        RenderSystem.stencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
        RenderSystem.stencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
    }

    /** Were clouds stamped this frame? If not, the patch has no cloud pass to draw. */
    static boolean markedThisFrame() {
        return marked;
    }

    /** The patch has drawn: the next frame stamps afresh. */
    static void frameDone() {
        marked = false;
    }
}
