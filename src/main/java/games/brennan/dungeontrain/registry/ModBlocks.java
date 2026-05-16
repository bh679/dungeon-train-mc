package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.narrative.block.NarrativeLecternBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Mod-side block registry. Wires custom blocks via the mod-event bus during
 * construction.
 *
 * <p>First (and currently only) entry: {@link #NARRATIVE_LECTERN} — a
 * progression-aware lectern variant. Reuses vanilla
 * {@link net.minecraft.world.level.block.entity.LecternBlockEntity} via
 * {@link games.brennan.dungeontrain.narrative.block.NarrativeLecternHooks}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(DungeonTrain.MOD_ID);
    public static final DeferredRegister.Items BLOCK_ITEMS = DeferredRegister.createItems(DungeonTrain.MOD_ID);

    /**
     * The narrative_lectern block. Identical render / hitbox to vanilla
     * lectern; lazy per-player book resolution on right-click.
     */
    public static final DeferredBlock<NarrativeLecternBlock> NARRATIVE_LECTERN = BLOCKS.register(
        "narrative_lectern",
        () -> new NarrativeLecternBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.LECTERN))
    );

    /** Matching {@link BlockItem} so the block is craftable / placeable. */
    public static final DeferredItem<BlockItem> NARRATIVE_LECTERN_ITEM = BLOCK_ITEMS.register(
        "narrative_lectern",
        () -> new BlockItem(NARRATIVE_LECTERN.get(), new Item.Properties())
    );

    private ModBlocks() {}

    /** Call from the mod constructor to attach both registers to the mod-event bus. */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        BLOCK_ITEMS.register(modBus);
    }

    /**
     * Add the narrative_lectern to the FUNCTIONAL_BLOCKS creative tab so OP
     * playtesters can grab it next to vanilla lectern.
     */
    @SubscribeEvent
    public static void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(NARRATIVE_LECTERN_ITEM.get());
        }
    }
}
