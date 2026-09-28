package games.brennan.dungeontrain.client.render;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.block.stage.StagePlaceholderBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import games.brennan.dungeontrain.client.menu.ClientStagePalette;
import games.brennan.dungeontrain.editor.StageBlockReplacer;
import net.neoforged.neoforge.registries.DeferredBlock;

import java.util.Map;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * Client wiring for {@link StagePlaceholderItemRenderer}: hands every stage placeholder item the
 * renderer, and registers the door's flat sprite model (no item model references it any more —
 * the placeholder item models are {@code builtin/entity}). Also lends every placeholder its
 * target's block colour, so a placed {@code stage_leaves} previews with the biome's foliage tint.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class StagePlaceholderItemClient {

    private StagePlaceholderItemClient() {}

    @SubscribeEvent
    public static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        Item[] items = StagePlaceholderBlocks.items().stream()
            .map(DeferredItem<BlockItem>::get).toArray(Item[]::new);
        event.registerItem(new IClientItemExtensions() {
            private StagePlaceholderItemRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) renderer = new StagePlaceholderItemRenderer();
                return renderer;
            }
        }, items);
    }

    @SubscribeEvent
    public static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event) {
        event.register(StagePlaceholderItemRenderer.DOOR_SPRITE);
    }

    /**
     * Tint a placed placeholder as its target: {@link StagePlaceholderBakedModel} draws the target's
     * quads, but the chunk renderer asks for colours by the placeholder's own state. The placeholder's
     * tile quads are untinted, so only the target's tinted faces (leaves) pick this up.
     */
    @SubscribeEvent
    public static void onRegisterBlockColors(RegisterColorHandlersEvent.Block event) {
        for (DeferredBlock<Block> holder : StagePlaceholderBlocks.blocks()) {
            Block block = holder.get();
            String name = BuiltInRegistries.BLOCK.getKey(block).getPath();
            event.register((state, level, pos, tintIndex) -> {
                BlockState target = targetState(state, name);
                return target == null ? -1
                    : Minecraft.getInstance().getBlockColors().getColor(target, level, pos, tintIndex);
            }, block);
        }
    }

    /** What {@code state} of placeholder {@code name} resolves to for the selected stage, or null. */
    private static BlockState targetState(BlockState state, String name) {
        String id = ClientStagePalette.resolved(name);
        ResourceLocation rl = id == null ? null : ResourceLocation.tryParse(id);
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) return null;
        Block target = BuiltInRegistries.BLOCK.get(rl);
        if (StagePlaceholderBlocks.isPlaceholder(target)) return null;
        return StageBlockReplacer.transfer(state, target);
    }

    /** Wrap every placeholder blockstate's model so placed placeholders blend with their target. */
    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        for (DeferredBlock<Block> holder : StagePlaceholderBlocks.blocks()) {
            Block block = holder.get();
            String name = BuiltInRegistries.BLOCK.getKey(block).getPath();
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                ModelResourceLocation key = BlockModelShaper.stateToModelLocation(state);
                BakedModel baked = models.get(key);
                if (baked != null && !(baked instanceof StagePlaceholderBakedModel)) {
                    models.put(key, new StagePlaceholderBakedModel(baked, name));
                }
            }
        }
    }
}
