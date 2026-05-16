package games.brennan.dungeontrain.narrative.block;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.registry.ModBlocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent;

/**
 * Mod-bus subscriber that adds {@link ModBlocks#NARRATIVE_LECTERN} to
 * {@link BlockEntityType#LECTERN}'s valid blocks. Without this, vanilla
 * {@link net.minecraft.world.level.block.entity.LecternBlockEntity}
 * instances won't bind to our custom block — placing one would result in
 * a missing BE and a render glitch.
 *
 * <p>{@link BlockEntityTypeAddBlocksEvent} is the NeoForge-blessed way to
 * extend a vanilla BE type's valid-block set without mixins or reflection.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class NarrativeLecternHooks {

    private NarrativeLecternHooks() {}

    @SubscribeEvent
    public static void onAddBlocks(BlockEntityTypeAddBlocksEvent event) {
        event.modify(BlockEntityType.LECTERN, ModBlocks.NARRATIVE_LECTERN.get());
    }
}
