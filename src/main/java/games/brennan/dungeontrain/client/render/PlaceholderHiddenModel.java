package games.brennan.dungeontrain.client.render;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Block model wrapper for the variant editor's empty-cell marker
 * ({@link games.brennan.dungeontrain.block.VariantPlaceholderBlock}): draws nothing for the one cell
 * the camera is inside ({@link PlaceholderCameraHide#hiddenCell()}), so standing in a marker you
 * see straight out of it. Every other marker draws as before. Installed over the marker's
 * blockstate by {@link PlaceholderCameraHide} via {@code ModelEvent.ModifyBakingResult}.
 *
 * <p>The cell is decided in {@link #getModelData}, the one model call that knows the position,
 * and carried to {@link #getQuads} as a model property. Item paths call {@code getQuads} without
 * model data and get the original quads, so the inventory icon is untouched.</p>
 */
public final class PlaceholderHiddenModel implements BakedModel {

    private static final ModelProperty<Boolean> HIDDEN = new ModelProperty<>();
    private static final ModelData HIDDEN_DATA = ModelData.builder().with(HIDDEN, true).build();

    private final BakedModel original;

    public PlaceholderHiddenModel(BakedModel original) {
        this.original = original;
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) {
        return pos.equals(PlaceholderCameraHide.hiddenCell()) ? HIDDEN_DATA : modelData;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
                                    ModelData data, @Nullable RenderType renderType) {
        if (Boolean.TRUE.equals(data.get(HIDDEN))) return List.of();
        return original.getQuads(state, side, rand, data, renderType);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
        return original.getQuads(state, side, rand);
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        return original.getRenderTypes(state, rand, data);
    }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) {
        return original.getParticleIcon(data);
    }

    // ---------------------------------------------------------------- straight delegation

    @Override public boolean useAmbientOcclusion() { return original.useAmbientOcclusion(); }
    @Override public boolean isGui3d() { return original.isGui3d(); }
    @Override public boolean usesBlockLight() { return original.usesBlockLight(); }
    @Override public boolean isCustomRenderer() { return original.isCustomRenderer(); }
    @Override public TextureAtlasSprite getParticleIcon() { return original.getParticleIcon(); }
    @Override public ItemTransforms getTransforms() { return original.getTransforms(); }
    @Override public ItemOverrides getOverrides() { return original.getOverrides(); }
}
