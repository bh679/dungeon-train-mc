package games.brennan.dungeontrain.mixin.client.sodium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import games.brennan.dungeontrain.client.UpsideDownRenderFlip;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * The upside-down band's per-block flip on <b>Sodium's</b> chunk-meshing pipeline — the same model
 * swap {@code ModelBlockRendererUpsideDownMixin} performs on the vanilla one.
 *
 * <p>Sodium replaces chunk meshing wholesale: {@code ChunkBuilderMeshingTask} looks the model up with
 * {@code BlockModelShaper.getBlockModel(state)} and hands it straight to {@code renderModel} below,
 * which emits quads through FRAPI ({@code emitBlockQuads} → {@code bufferDefaultModel} →
 * {@code BakedModel.getQuads}). {@code ModelBlockRenderer.tesselateBlock} is never called, so without
 * this mixin the whole band renders upright wherever Sodium is installed — which is every install with
 * <b>Iris shaders</b> (Iris requires Sodium) and every install of the Dungeon Train modpack, which
 * ships both. Because Sodium ends up calling {@code getQuads} on whatever model it was given, the
 * existing {@link games.brennan.dungeontrain.client.UpsideDownBakedModel} wrapper works verbatim here;
 * it only needs handing over.</p>
 *
 * <p>Targeted by class name, and every parameter of {@code renderModel} is a vanilla type, so Sodium is
 * <b>not</b> a compile dependency. The third argument is the world position (the fourth is the
 * section-local origin). Applied only when Sodium is loaded ({@code SodiumMixinPlugin}), and the config
 * sets {@code defaultRequire: 0} so a future Sodium refactor of this method degrades to "no flip under
 * Sodium" rather than failing the client's launch.</p>
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer", remap = false)
public abstract class BlockRendererUpsideDownMixin {

    @WrapMethod(method = "renderModel")
    private void dungeontrain$flipUpsideDownBand(BakedModel model, BlockState state, BlockPos pos,
                                                 BlockPos origin, Operation<Void> original) {
        original.call(UpsideDownRenderFlip.apply(model, pos), state, pos, origin);
    }
}
