package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.narrative.block.NarrativeLecternBlock;
import games.brennan.dungeontrain.platform.DtRegistrar;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Mod-side block registry. Registered via {@link DtRegistrar} (loader-neutral)
 * instead of a direct {@code DeferredRegister} — see
 * {@link games.brennan.dungeontrain.advancement.ModAdvancementTriggers} for
 * the pattern and the root attach timing.
 *
 * <p>First (and currently only) entry: {@link #NARRATIVE_LECTERN} — a
 * progression-aware lectern variant. Reuses vanilla
 * {@link net.minecraft.world.level.block.entity.LecternBlockEntity} via
 * {@link games.brennan.dungeontrain.narrative.block.NarrativeLecternHooks}.</p>
 */
public final class ModBlocks {

    /**
     * The narrative_lectern block. Identical render / hitbox to vanilla
     * lectern; lazy per-player book resolution on right-click.
     */
    public static final Supplier<NarrativeLecternBlock> NARRATIVE_LECTERN = DtRegistrar.get().register(
        Registries.BLOCK,
        "narrative_lectern",
        () -> new NarrativeLecternBlock(
            BlockBehaviour.Properties.ofFullCopy(Blocks.LECTERN)
                .lightLevel(s -> 12)
        )
    );

    /** Matching {@link BlockItem} so the block is craftable / placeable. */
    public static final Supplier<BlockItem> NARRATIVE_LECTERN_ITEM = DtRegistrar.get().register(
        Registries.ITEM,
        "narrative_lectern",
        () -> new BlockItem(NARRATIVE_LECTERN.get(), new Item.Properties())
    );

    private ModBlocks() {}

    /** Call from the mod constructor to force this class's static fields (and their registrations) to run. */
    public static void init() {}

    /**
     * Add the narrative_lectern to the FUNCTIONAL_BLOCKS creative tab so OP
     * playtesters can grab it next to vanilla lectern.
     */
    public static void onBuildCreativeTabs(ResourceKey<CreativeModeTab> tabKey, Consumer<ItemStack> output) {
        if (tabKey == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            output.accept(new ItemStack(NARRATIVE_LECTERN_ITEM.get()));
        }
    }
}
