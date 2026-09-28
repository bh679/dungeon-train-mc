package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Vec3i;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;

/**
 * A baked template mesh, drawn inside one tile of the Open grid.
 *
 * <p>Orthographic, not perspective. The tile is about a hundred pixels wide and the thing in it is
 * a building; a perspective frustum at that size mostly buys you a wonky vanishing point. Flat
 * projection with a fixed three-quarter tilt is how a build catalogue reads, and it makes the
 * fit-to-cell maths exact rather than approximate.</p>
 *
 * <h3>Depth</h3>
 *
 * <p>The GUI shares one depth buffer with everything else on the screen, and a model with real Z
 * extent leaves marks in it that the flat overlays drawn afterwards — the hover dim, the border,
 * the label strip — would then fail the depth test against. So the tile's depth is wiped after the
 * model is drawn, inside the scissor box, which costs a scissored clear of about a hundred pixels
 * square and lets the 2D drawing that follows behave exactly as it always has.</p>
 */
@OnlyIn(Dist.CLIENT)
final class BuilderTileModelRenderer {

    /** Tilt, in degrees: looking slightly down on the build, as a catalogue photo would. */
    private static final float PITCH = 22.0F;

    /**
     * How much of the cell the model is allowed to fill.
     *
     * <p>Under 1 so a rotating build never clips its own corners against the cell edge — the
     * footprint of a box sweeps wider as it turns, and a model sized to fit at rest would saw
     * through the border every quarter turn.</p>
     */
    private static final float FILL = 0.62F;

    /**
     * The least the model is pushed towards the viewer, so it sits above whatever the tile drew
     * underneath it.
     *
     * <p>A floor, not the offset itself. The model spans about half its scaled diagonal either side
     * of the pivot, and at preview size — six to eleven pixels a block — a room's far corners reach
     * past a fixed hundred and are clipped behind the backdrop. {@link #applyTransform} pushes
     * further whenever the scaled model needs it; there are thousands of units of headroom towards
     * the viewer in the GUI's projection.</p>
     */
    private static final float MIN_Z_OFFSET = 100.0F;

    private BuilderTileModelRenderer() {}

    /**
     * Draw {@code mesh} centred in the cell, turned to {@code yaw} degrees.
     *
     * <p>Leaves the GUI as it found it: the caller's next {@code fill} or {@code drawString} needs
     * no special handling.</p>
     */
    static void render(GuiGraphics g, BuilderTileMesh mesh, int x, int y, int width, int height,
                       float yaw) {
        render(g, mesh, x, y, width, height, yaw, FILL);
    }

    /**
     * As {@link #render(GuiGraphics, BuilderTileMesh, int, int, int, int, float)} with an explicit
     * share of the cell for the model to fill — the preview pane has no border to saw through and
     * can afford more than a grid tile.
     */
    static void render(GuiGraphics g, BuilderTileMesh mesh, int x, int y, int width, int height,
                       float yaw, float fill) {
        // Everything queued so far must land before the 3D pass, or it would be flushed on top of
        // the model afterwards.
        g.flush();
        g.enableScissor(x, y, x + width, y + height);

        PoseStack pose = g.pose();
        pose.pushPose();
        applyTransform(pose, mesh.size(), mesh.min(), x, y, width, height, yaw, fill);

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        // drawWithShader overrides the shader's model-view outright, so the GUI's own model-view —
        // which carries the ortho projection's Z offset — has to be folded in by hand or the model
        // lands outside the near plane and nothing appears.
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.last().pose());
        mesh.draw(modelView, RenderSystem.getProjectionMatrix());

        pose.popPose();

        // Scissored, so this is the tile's depth and not the screen's. See the class note.
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        g.disableScissor();
    }

    /**
     * Centre the build in the cell, scale it to fit, and turn it.
     *
     * <p><b>The negative Y scale is deliberate — do not "fix" it.</b> It mirrors the model, and a
     * mirror is exactly what this needs: GUI space is left-handed (X right, Y <em>down</em>, Z
     * towards the viewer) where block models are authored right-handed, so one flip somewhere is
     * required to make triangle winding come out facing the camera. Replacing this with a proper
     * rotation — a half turn about X, which puts every vertex in the identical place — leaves the
     * handedness wrong and backface culling then shows you the inside of every block. Vanilla does
     * the same thing for entities in inventories: {@code scale(size, size, -size)}.</p>
     *
     * <p>The scale is driven by the model's longest horizontal diagonal rather than its width, so a
     * long carriage and a squat room both stay inside the cell through a full rotation.</p>
     */
    private static void applyTransform(PoseStack pose, Vec3i size, Vec3i min, int x, int y,
                                       int width, int height, float yaw, float fill) {
        float centreX = x + width / 2.0F;
        float centreY = y + height / 2.0F;

        float diagonal = (float) Math.sqrt((double) size.getX() * size.getX()
                + (double) size.getZ() * size.getZ());
        float tall = size.getY();
        // The tilt lifts the footprint into the vertical budget, so the height the model occupies
        // on screen is its own height plus what the tilted floor contributes.
        float pitchRadians = (float) Math.toRadians(PITCH);
        float occupiedY = tall * (float) Math.cos(pitchRadians)
                + diagonal * (float) Math.sin(pitchRadians);
        float scale = Math.min(width * fill / Math.max(diagonal, 1.0F),
                height * fill / Math.max(occupiedY, 1.0F));

        pose.translate(centreX, centreY, zOffsetFor(scale, diagonal, tall));
        pose.scale(scale, -scale, scale);
        pose.mulPose(new Quaternionf().rotateX(pitchRadians));
        pose.mulPose(new Quaternionf().rotateY((float) Math.toRadians(yaw)));
        // The mesh is built in the template's own coordinates, so shift the middle of the occupied
        // box — not of the file's declared bounds — onto the pivot.
        pose.translate(-(min.getX() + size.getX() / 2.0F),
                -(min.getY() + size.getY() / 2.0F),
                -(min.getZ() + size.getZ() / 2.0F));
    }

    /** How far towards the viewer a model this size must sit so no corner falls behind z = 0. */
    static float zOffsetFor(float scale, float diagonal, float tall) {
        return Math.max(MIN_Z_OFFSET, scale * (diagonal + tall) / 2.0F + 1.0F);
    }
}
