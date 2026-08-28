package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.RandomSource;
import org.joml.Matrix4f;

/**
 * A star field for the custom sky sources, generated the way vanilla generates its own.
 *
 * <p>Vanilla's stars live in {@code LevelRenderer}'s private {@code starBuffer}, uploaded once at
 * construction and drawn only through its own sky pass — there is no seam to reuse it from, so
 * this reproduces the generator: a fixed seed, 1500 small quads scattered on a unit sphere at
 * radius {@value #RADIUS}, each spun to a random roll. Same seed as vanilla, so the constellations
 * a player sees through a night skybox block are the ones they already know from the real sky.</p>
 *
 * <p>The geometry is built once into {@link #vertices} — deterministic, so a single static array
 * is safe to share — and re-emitted through the tesselator each frame with the caller's pose. It
 * is deliberately <em>not</em> a {@code VertexBuffer}: the surrounding sky renderers all draw
 * through {@link BufferUploader}, which picks up the current matrices, whereas a vertex buffer
 * would need the projection matrix passed down through every call site for no measurable gain at
 * this vertex count.</p>
 *
 * <p>The caller owns the rotation, and that is the whole point of keeping this separate: the
 * night sky wheels its stars on vanilla's overhead axis, the sunrise sky sweeps the same stars
 * sideways around the horizon.</p>
 */
public final class StarFieldRenderer {

    /** Vanilla's star count, seed and sphere radius — matched so the sky reads as familiar. */
    private static final int STAR_COUNT = 1500;
    private static final long SEED = 10842L;
    private static final double RADIUS = 100.0;

    /** {@code x, y, z} per vertex, four vertices per star. Built lazily, then immutable in practice. */
    private static float[] vertices;

    private StarFieldRenderer() {}

    /**
     * Draw the whole field at {@code alpha}, in whatever frame {@code pose} describes.
     *
     * <p>Additive blending, like the sun and moon: stars should brighten the sky behind them
     * rather than punch alpha-composited holes in it, which is also what lets a low alpha read as
     * "faint stars" instead of "grey squares".</p>
     */
    public static void draw(Matrix4f pose, float alpha) {
        if (alpha <= 0.0F) return;
        float[] verts = build();

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE,
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ZERO);
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        int color = SkyDomeDraw.argb(alpha, 0xFFFFFF);
        BufferBuilder bb = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < verts.length; i += 3) {
            bb.addVertex(pose, verts[i], verts[i + 1], verts[i + 2]).setColor(color);
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());

        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc(); // restore standard alpha blend so additive state doesn't leak
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Vanilla's star placement, vertex for vertex (see {@code LevelRenderer.drawStars}): reject
     * samples outside the unit ball so the distribution is spherical rather than cubic, project
     * each survivor onto the sphere, then build a small quad facing the origin and rolled by a
     * random angle.
     */
    private static float[] build() {
        float[] cached = vertices;
        if (cached != null) return cached;

        RandomSource random = RandomSource.create(SEED);
        java.util.ArrayList<Float> out = new java.util.ArrayList<>(STAR_COUNT * 12);

        for (int i = 0; i < STAR_COUNT; i++) {
            double x = random.nextFloat() * 2.0F - 1.0F;
            double y = random.nextFloat() * 2.0F - 1.0F;
            double z = random.nextFloat() * 2.0F - 1.0F;
            double size = 0.15F + random.nextFloat() * 0.1F;
            double lengthSq = x * x + y * y + z * z;
            if (lengthSq >= 1.0 || lengthSq <= 0.01) continue;

            double norm = 1.0 / Math.sqrt(lengthSq);
            x *= norm;
            y *= norm;
            z *= norm;
            double cx = x * RADIUS;
            double cy = y * RADIUS;
            double cz = z * RADIUS;

            double yaw = Math.atan2(x, z);
            double sinYaw = Math.sin(yaw);
            double cosYaw = Math.cos(yaw);
            double pitch = Math.atan2(Math.sqrt(x * x + z * z), y);
            double sinPitch = Math.sin(pitch);
            double cosPitch = Math.cos(pitch);
            double roll = random.nextDouble() * Math.PI * 2.0;
            double sinRoll = Math.sin(roll);
            double cosRoll = Math.cos(roll);

            for (int corner = 0; corner < 4; corner++) {
                double cornerX = ((corner & 2) - 1) * size;
                double cornerY = ((corner + 1 & 2) - 1) * size;
                double rolledX = cornerX * cosRoll - cornerY * sinRoll;
                double rolledY = cornerY * cosRoll + cornerX * sinRoll;
                double tiltedY = rolledX * sinPitch;
                double tiltedZ = -rolledX * cosPitch;
                out.add((float) (cx + tiltedZ * sinYaw - rolledY * cosYaw));
                out.add((float) (cy + tiltedY));
                out.add((float) (cz + rolledY * sinYaw + tiltedZ * cosYaw));
            }
        }

        float[] built = new float[out.size()];
        for (int i = 0; i < built.length; i++) built[i] = out.get(i);
        vertices = built;
        return built;
    }
}
