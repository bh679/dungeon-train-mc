package games.brennan.dungeontrain.client.render;

import games.brennan.dungeontrain.block.stage.StagePlaceholderBlocks;
import games.brennan.dungeontrain.client.menu.ClientStagePalette;
import games.brennan.dungeontrain.editor.StageBlockReplacer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Block model wrapper for a placed stage placeholder: in the world it renders as the block the
 * placeholder resolves to for the editor's effective stage ({@link ClientStagePalette}) with the
 * placeholder's own tile blended over it at {@link #BLEND_ALPHA} — the build reads both as "a
 * placeholder" and as "what it becomes". With no stage in scope (outside the editor) it is the
 * plain placeholder model. Installed over every placeholder blockstate by
 * {@link StagePlaceholderItemClient} via {@code ModelEvent.ModifyBakingResult}.
 *
 * <p>Layering: the target's quads go to the target model's own render layers; the placeholder's
 * quads, alpha-rewritten, go to {@link RenderType#translucent()}. They are coplanar, and the
 * translucent pass draws after the solid ones with LEQUAL depth, so the tile blends over the
 * target. Item paths call {@code getQuads} with a null render type and get the original quads,
 * which keeps the inventory ghost ({@link StagePlaceholderItemRenderer}) the plain tile.</p>
 */
public final class StagePlaceholderBakedModel implements BakedModel {

    /** Opacity of the placeholder tile over the target block. */
    public static final float BLEND_ALPHA = 0.5f;

    private static final ChunkRenderTypeSet TRANSLUCENT = ChunkRenderTypeSet.of(RenderType.translucent());

    private final BakedModel original;
    private final String name;

    public StagePlaceholderBakedModel(BakedModel original, String name) {
        this.original = original;
        this.name = name;
    }

    /** The target block state for {@code state} under the effective stage, or null when unresolved. */
    @Nullable
    private BlockState targetState(@Nullable BlockState state) {
        if (state == null) return null;
        String id = ClientStagePalette.resolved(name);
        if (id == null) return null;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) return null;
        Block target = BuiltInRegistries.BLOCK.get(rl);
        if (StagePlaceholderBlocks.isPlaceholder(target) || target == state.getBlock()) return null;
        return StageBlockReplacer.transfer(state, target);
    }

    private static BakedModel modelOf(BlockState state) {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
                                    ModelData data, @Nullable RenderType renderType) {
        if (renderType == null) return original.getQuads(state, side, rand, data, null);
        BlockState target = targetState(state);
        if (target == null) return original.getQuads(state, side, rand, data, renderType);
        List<BakedQuad> out = new ArrayList<>();
        BakedModel targetModel = modelOf(target);
        if (targetModel.getRenderTypes(target, rand, ModelData.EMPTY).contains(renderType)) {
            out.addAll(targetModel.getQuads(target, side, rand, ModelData.EMPTY, renderType));
        }
        if (renderType == RenderType.translucent()) {
            // The placeholder's own quads (whichever layer they were authored for), faded.
            for (RenderType own : original.getRenderTypes(state, rand, data)) {
                for (BakedQuad q : original.getQuads(state, side, rand, data, own)) out.add(faded(q));
            }
        }
        return out;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
        return original.getQuads(state, side, rand);
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        BlockState target = targetState(state);
        if (target == null) return original.getRenderTypes(state, rand, data);
        return ChunkRenderTypeSet.union(modelOf(target).getRenderTypes(target, rand, ModelData.EMPTY), TRANSLUCENT);
    }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) {
        return original.getParticleIcon(data);
    }

    /** Copy of {@code q} with every vertex's colour alpha set to {@link #BLEND_ALPHA}. */
    private static BakedQuad faded(BakedQuad q) {
        int[] v = q.getVertices().clone();
        int stride = v.length / 4;
        int alpha = Math.round(BLEND_ALPHA * 255f) << 24;
        for (int i = 0; i < 4; i++) {
            int c = i * stride + 3; // colour int follows x, y, z
            v[c] = (v[c] & 0x00FFFFFF) | alpha;
        }
        return new BakedQuad(v, q.getTintIndex(), q.getDirection(), q.getSprite(), q.isShade(), q.hasAmbientOcclusion());
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
