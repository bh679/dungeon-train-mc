package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.block.SkyboxBlock;
import games.brennan.dungeontrain.block.SkyboxSky;
import games.brennan.dungeontrain.narrative.block.NarrativeLecternBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
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
     * Shared properties for every skybox variant.
     *
     * <p>{@code strength(-1, 3600000.8)} is vanilla's bedrock/barrier pair — negative destroy
     * time makes it unmineable in survival (creative can still break it) and the resistance
     * makes it blast-proof. Deliberately <b>no</b> {@code noOcclusion()}: we want vanilla to
     * treat it as opaque so it culls neighbouring faces and whole sections behind the hole.</p>
     */
    private static BlockBehaviour.Properties skyboxProperties(MapColor color) {
        return BlockBehaviour.Properties.of()
            .mapColor(color)
            .strength(-1.0F, 3600000.8F)
            .noLootTable()
            .noTerrainParticles()
            .isValidSpawn((state, level, pos, type) -> false)
            .pushReaction(PushReaction.BLOCK)
            .sound(SoundType.GLASS);
    }

    /** The above-ground overworld sky, correct at any Y. See {@link SkyboxSky#SURFACE}. */
    public static final DeferredBlock<SkyboxBlock> SKYBOX_BLOCK = BLOCKS.register(
        SkyboxSky.SURFACE.blockName(),
        () -> new SkyboxBlock(SkyboxSky.SURFACE, skyboxProperties(MapColor.COLOR_LIGHT_BLUE))
    );

    /** The End starfield. */
    public static final DeferredBlock<SkyboxBlock> SKYBOX_END = BLOCKS.register(
        SkyboxSky.END.blockName(),
        () -> new SkyboxBlock(SkyboxSky.END, skyboxProperties(MapColor.COLOR_BLACK))
    );

    /** The Nether sky. */
    public static final DeferredBlock<SkyboxBlock> SKYBOX_NETHER = BLOCKS.register(
        SkyboxSky.NETHER.blockName(),
        () -> new SkyboxBlock(SkyboxSky.NETHER, skyboxProperties(MapColor.COLOR_RED))
    );

    /** The upside-down band's sky, sun and moon orbiting the horizon. */
    public static final DeferredBlock<SkyboxBlock> SKYBOX_UPSIDE_DOWN = BLOCKS.register(
        SkyboxSky.UPSIDE_DOWN.blockName(),
        () -> new SkyboxBlock(SkyboxSky.UPSIDE_DOWN, skyboxProperties(MapColor.COLOR_LIGHT_BLUE))
    );

    /** Matching {@link BlockItem}s so the blocks can be placed from the creative tab. */
    public static final DeferredItem<BlockItem> SKYBOX_BLOCK_ITEM = BLOCK_ITEMS.register(
        SkyboxSky.SURFACE.blockName(), () -> new BlockItem(SKYBOX_BLOCK.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> SKYBOX_END_ITEM = BLOCK_ITEMS.register(
        SkyboxSky.END.blockName(), () -> new BlockItem(SKYBOX_END.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> SKYBOX_NETHER_ITEM = BLOCK_ITEMS.register(
        SkyboxSky.NETHER.blockName(), () -> new BlockItem(SKYBOX_NETHER.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> SKYBOX_UPSIDE_DOWN_ITEM = BLOCK_ITEMS.register(
        SkyboxSky.UPSIDE_DOWN.blockName(), () -> new BlockItem(SKYBOX_UPSIDE_DOWN.get(), new Item.Properties()));

    private ModBlocks() {}

    /** Call from the mod constructor to attach both registers to the mod-event bus. */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        BLOCK_ITEMS.register(modBus);
    }

    /**
     * Add DT's blocks to the FUNCTIONAL_BLOCKS creative tab so OP playtesters can
     * grab them next to their vanilla counterparts.
     */
    @SubscribeEvent
    public static void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(NARRATIVE_LECTERN_ITEM.get());
            event.accept(SKYBOX_BLOCK_ITEM.get());
            event.accept(SKYBOX_END_ITEM.get());
            event.accept(SKYBOX_NETHER_ITEM.get());
            event.accept(SKYBOX_UPSIDE_DOWN_ITEM.get());
        }
    }
}
