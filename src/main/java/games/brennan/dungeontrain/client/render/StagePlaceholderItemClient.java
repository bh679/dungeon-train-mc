package games.brennan.dungeontrain.client.render;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.block.stage.StagePlaceholderBlocks;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * Client wiring for {@link StagePlaceholderItemRenderer}: hands every stage placeholder item the
 * renderer, and registers the door's flat sprite model (no item model references it any more —
 * the placeholder item models are {@code builtin/entity}).
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
}
