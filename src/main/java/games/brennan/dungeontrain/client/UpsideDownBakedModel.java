package games.brennan.dungeontrain.client;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link BakedModelWrapper} that renders a block <b>vertically flipped</b> — the upside-down band's
 * per-block visual flip. Applied only to blocks in the band by
 * {@code ModelBlockRendererUpsideDownMixin}, which passes this wrapper as the model.
 *
 * <p>Rotating the block geometry in the render can't work: face-culling has already dropped the
 * hidden faces, so a rotation swings the one surviving (air-adjacent) face to the far side, leaving
 * the block looking inside-out. Instead this flips at the <b>model</b> level: for a requested cull
 * side it returns the quads of the vertically-opposite face, each quad mirrored about the block's
 * mid-height (y → 1−y), its winding reversed (so it stays front-facing after the mirror), its normal
 * Y negated, and its {@link BakedQuad#getDirection() direction} flipped. Culling is therefore
 * unchanged (still keyed by the real neighbours), and a grass block's top face is what renders on its
 * now-downward air side. Same face count as normal → no extra per-frame cost; the mirrored quads are
 * baked into the section mesh.</p>
 */
public final class UpsideDownBakedModel extends BakedModelWrapper<BakedModel> {

    /** Wrappers are cached per source model so meshing doesn't allocate one per block. */
    private static final Map<BakedModel, BakedModel> CACHE = new ConcurrentHashMap<>();

    private UpsideDownBakedModel(BakedModel original) {
        super(original);
    }

    /** Get (or create) the vertically-flipped wrapper for {@code base}. */
    public static BakedModel of(BakedModel base) {
        return CACHE.computeIfAbsent(base, UpsideDownBakedModel::new);
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, @Nullable Direction side, RandomSource rand) {
        return flip(this.originalModel.getQuads(state, flipY(side), rand));
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, @Nullable Direction side, RandomSource rand,
                                    ModelData data, @Nullable RenderType renderType) {
        return flip(this.originalModel.getQuads(state, flipY(side), rand, data, renderType));
    }

    /** UP↔DOWN; horizontal faces and {@code null} unchanged. */
    @Nullable
    private static Direction flipY(@Nullable Direction side) {
        if (side == Direction.UP) return Direction.DOWN;
        if (side == Direction.DOWN) return Direction.UP;
        return side;
    }

    private static List<BakedQuad> flip(List<BakedQuad> src) {
        if (src.isEmpty()) return src;
        List<BakedQuad> out = new ArrayList<>(src.size());
        for (BakedQuad q : src) {
            out.add(flipQuad(q));
        }
        return out;
    }

    private static BakedQuad flipQuad(BakedQuad q) {
        int[] in = q.getVertices();
        int stride = IQuadTransformer.STRIDE;         // ints per vertex (8 for the BLOCK format)
        int posY = IQuadTransformer.POSITION + 1;     // Y is the 2nd position float
        int nrm = IQuadTransformer.NORMAL;            // packed normal int
        int verts = in.length / stride;
        int[] outV = new int[in.length];
        for (int i = 0; i < verts; i++) {
            int srcBase = i * stride;
            int dstBase = (verts - 1 - i) * stride;   // reverse winding to stay front-facing after the Y mirror
            System.arraycopy(in, srcBase, outV, dstBase, stride);
            // Mirror the vertex about the block's mid-height (y → 1 − y); UVs are kept, so the texture
            // reads vertically flipped exactly where we want it.
            float y = Float.intBitsToFloat(outV[dstBase + posY]);
            outV[dstBase + posY] = Float.floatToRawIntBits(1.0F - y);
            // Negate the packed normal's Y byte so lighting/shading matches the flipped face.
            int n = outV[dstBase + nrm];
            int ny = (byte) ((n >> 8) & 0xFF);
            outV[dstBase + nrm] = (n & ~0x0000FF00) | ((-ny & 0xFF) << 8);
        }
        return new BakedQuad(outV, q.getTintIndex(), flipY(q.getDirection()), q.getSprite(),
                q.isShade(), q.hasAmbientOcclusion());
    }
}
