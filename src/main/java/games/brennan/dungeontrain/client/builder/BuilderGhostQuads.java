package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.MenuRenderStates;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * How the Train Builder draws a ghost: pale translucent quads on the faces a solid would show.
 *
 * <p>The builder ghosts two different things — the rest of the carriage group around a single
 * parked carriage, and the rest of the line around an open track template — and they have to look
 * like the same idea, because they mean the same thing: <i>this is context, not what you are
 * editing</i>. Two renderers each with their own colour and alpha would say they were two different
 * kinds of thing.</p>
 *
 * <p>Flat quads rather than the block's own model, deliberately. A ghost is a silhouette aid, and a
 * textured half-alpha copy of a block reads as a real block that has gone strange — which is the one
 * thing a ghost must never be mistaken for. It also keeps the pass renderer-agnostic: no chunk
 * layer, no shader assumptions, nothing for Sodium to replace.</p>
 *
 * <p>Only faces onto air should be emitted by callers. The inside of a solid mass is invisible
 * anyway, and at this alpha a second layer of it reads as a darker stripe rather than as depth.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderGhostQuads {

    /** Pulls the quad just clear of the neighbouring face so the two don't z-fight. */
    public static final double EXPAND = 0.002;

    private static final float R = 0.80F;
    private static final float G = 0.86F;
    private static final float B = 1.00F;
    /** 10% — present enough to read the silhouette, faint enough never to be mistaken for a block. */
    public static final float A = 0.10F;

    private BuilderGhostQuads() {}

    /**
     * A ghost batch of this mod's standard translucent quad type.
     *
     * <p>Each renderer passes its own name so they stay separate batches in the buffer source.</p>
     */
    public static RenderType renderType(String name) {
        return MenuRenderStates.translucentQuad(DungeonTrain.MOD_ID + ":" + name);
    }

    /** One outset unit square on the given side of the block at {@code (bx, by, bz)}. */
    public static void drawFace(PoseStack ps, VertexConsumer vc, int bx, int by, int bz, Direction dir) {
        drawFace(ps, vc, bx, by, bz, dir, A);
    }

    /**
     * As {@link #drawFace(PoseStack, VertexConsumer, int, int, int, Direction)}, at a chosen opacity.
     *
     * <p>The colour stays this class's — what varies between ghosts is how present they are, not what
     * they are. The room-mode ghosts need this because their whole window fades with distance: the
     * copies you can touch are nearly solid and the far ones dissolve, which is what says "this goes
     * on further than I am drawing it" without a hard edge.</p>
     */
    public static void drawFace(PoseStack ps, VertexConsumer vc, int bx, int by, int bz,
                                Direction dir, float alpha) {
        double x0 = bx - EXPAND;
        double y0 = by - EXPAND;
        double z0 = bz - EXPAND;
        double x1 = bx + 1.0 + EXPAND;
        double y1 = by + 1.0 + EXPAND;
        double z1 = bz + 1.0 + EXPAND;

        switch (dir) {
            case DOWN -> quad(ps, vc, alpha, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
            case UP -> quad(ps, vc, alpha, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);
            case NORTH -> quad(ps, vc, alpha, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);
            case SOUTH -> quad(ps, vc, alpha, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            case WEST -> quad(ps, vc, alpha, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            case EAST -> quad(ps, vc, alpha, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);
        }
    }

    /** The six outer faces of a box at {@code min} spanning {@code size}, at a chosen opacity. */
    public static void drawBox(PoseStack ps, VertexConsumer vc,
                               net.minecraft.core.BlockPos min, net.minecraft.core.Vec3i size,
                               float alpha) {
        double x0 = min.getX() - EXPAND;
        double y0 = min.getY() - EXPAND;
        double z0 = min.getZ() - EXPAND;
        double x1 = min.getX() + size.getX() + EXPAND;
        double y1 = min.getY() + size.getY() + EXPAND;
        double z1 = min.getZ() + size.getZ() + EXPAND;

        quad(ps, vc, alpha, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);   // down
        quad(ps, vc, alpha, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);   // up
        quad(ps, vc, alpha, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);   // north
        quad(ps, vc, alpha, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);   // south
        quad(ps, vc, alpha, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);   // west
        quad(ps, vc, alpha, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);   // east
    }

    private static void quad(PoseStack ps, VertexConsumer vc, float alpha,
                             double ax, double ay, double az, double bx, double by, double bz,
                             double cx, double cy, double cz, double dx, double dy, double dz) {
        org.joml.Matrix4f m = ps.last().pose();
        vc.addVertex(m, (float) ax, (float) ay, (float) az).setColor(R, G, B, alpha);
        vc.addVertex(m, (float) bx, (float) by, (float) bz).setColor(R, G, B, alpha);
        vc.addVertex(m, (float) cx, (float) cy, (float) cz).setColor(R, G, B, alpha);
        vc.addVertex(m, (float) dx, (float) dy, (float) dz).setColor(R, G, B, alpha);
    }
}
