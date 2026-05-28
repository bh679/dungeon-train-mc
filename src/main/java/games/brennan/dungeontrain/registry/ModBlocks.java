package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.narrative.block.NarrativeLecternBlock;
import games.brennan.dungeontrain.portal.DimensionalPortalCoreBlock;
import games.brennan.dungeontrain.portal.DimensionalPortalFrameBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
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
 * <p>Entries:
 * <ul>
 *   <li>{@link #NARRATIVE_LECTERN} — progression-aware lectern variant.
 *       Reuses vanilla {@link net.minecraft.world.level.block.entity.LecternBlockEntity}
 *       via {@link games.brennan.dungeontrain.narrative.block.NarrativeLecternHooks}.</li>
 *   <li>{@link #DIMENSIONAL_PORTAL_FRAME} — decorative perimeter block of the
 *       cross-dimension train portal (Phase 1 of the portal feature). Obsidian-
 *       like strength + indestructible-by-explosion.</li>
 *   <li>{@link #DIMENSIONAL_PORTAL_CORE} — pass-through swirl block with a
 *       {@link games.brennan.dungeontrain.portal.DimensionalPortalBlockEntity}.
 *       Indestructible (bedrock-style) since destroying a paired portal at
 *       runtime would leave the carriage transit logic with a dangling
 *       partner reference; the registry handles teardown explicitly.</li>
 * </ul>
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
        () -> new NarrativeLecternBlock(
            BlockBehaviour.Properties.ofFullCopy(Blocks.LECTERN)
                .lightLevel(s -> 12)
        )
    );

    /** Matching {@link BlockItem} so the block is craftable / placeable. */
    public static final DeferredItem<BlockItem> NARRATIVE_LECTERN_ITEM = BLOCK_ITEMS.register(
        "narrative_lectern",
        () -> new BlockItem(NARRATIVE_LECTERN.get(), new Item.Properties())
    );

    /**
     * Decorative perimeter block of a dimensional train portal. Obsidian
     * base with elevated blast resistance + ambient light. Not part of the
     * transit-detection logic — frames are purely cosmetic in v1.
     */
    public static final DeferredBlock<DimensionalPortalFrameBlock> DIMENSIONAL_PORTAL_FRAME = BLOCKS.register(
        "dimensional_portal_frame",
        () -> new DimensionalPortalFrameBlock(
            BlockBehaviour.Properties.ofFullCopy(Blocks.OBSIDIAN)
                .lightLevel(s -> 4)
                .explosionResistance(3000.0F)
        )
    );

    public static final DeferredItem<BlockItem> DIMENSIONAL_PORTAL_FRAME_ITEM = BLOCK_ITEMS.register(
        "dimensional_portal_frame",
        () -> new BlockItem(DIMENSIONAL_PORTAL_FRAME.get(), new Item.Properties())
    );

    /**
     * Active centre block. Backed by {@link games.brennan.dungeontrain.portal.DimensionalPortalBlockEntity}.
     * Bedrock-style indestructible at runtime — pairing/de-pairing is the
     * registry's responsibility, not the player's.
     */
    public static final DeferredBlock<DimensionalPortalCoreBlock> DIMENSIONAL_PORTAL_CORE = BLOCKS.register(
        "dimensional_portal_core",
        () -> new DimensionalPortalCoreBlock(
            BlockBehaviour.Properties.ofFullCopy(Blocks.NETHER_PORTAL)
                .lightLevel(s -> 11)
                .noCollission()
                .noLootTable()
                .strength(-1.0F, 3600000.0F)
                .pushReaction(PushReaction.BLOCK)
        )
    );

    public static final DeferredItem<BlockItem> DIMENSIONAL_PORTAL_CORE_ITEM = BLOCK_ITEMS.register(
        "dimensional_portal_core",
        () -> new BlockItem(DIMENSIONAL_PORTAL_CORE.get(), new Item.Properties())
    );

    private ModBlocks() {}

    /** Call from the mod constructor to attach both registers to the mod-event bus. */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        BLOCK_ITEMS.register(modBus);
    }

    /**
     * Add custom blocks to creative tabs so OP playtesters can grab them.
     * Narrative lectern lives in FUNCTIONAL_BLOCKS (next to vanilla lectern);
     * portal blocks live in BUILDING_BLOCKS to sit next to obsidian/nether
     * portal references.
     */
    @SubscribeEvent
    public static void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(NARRATIVE_LECTERN_ITEM.get());
        }
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(DIMENSIONAL_PORTAL_FRAME_ITEM.get());
            event.accept(DIMENSIONAL_PORTAL_CORE_ITEM.get());
        }
    }
}
