package games.brennan.dungeontrain.editor;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What a flower pot can hold, read from the empty pot's own plant map
 * ({@link FlowerPotBlock#getFullPotsView}) — the map vanilla's right-click uses, which modded
 * potted plants (Biomes O' Plenty, BetterNether, …) register into. A plant is named by its block
 * id ({@code minecraft:poppy}); the potted block is looked up from it.
 */
public final class FlowerPots {

    private static final ResourceLocation AIR_ID = ResourceLocation.withDefaultNamespace("air");

    private FlowerPots() {}

    /** True for an empty or planted flower pot — the gate for the Plant pill and the spawn pass. */
    public static boolean isPot(@Nullable BlockState state) {
        return state != null && state.getBlock() instanceof FlowerPotBlock;
    }

    /** Every plant a pot accepts, sorted by id; empty-pot (air) excluded. */
    public static List<ResourceLocation> allPlants() {
        List<ResourceLocation> out = new ArrayList<>();
        for (ResourceLocation id : plantMap().keySet()) {
            if (!id.equals(AIR_ID) && pottedFor(id) != null) out.add(id);
        }
        out.sort(ResourceLocation::compareNamespaced);
        return out;
    }

    /** The potted block for {@code plantId}, or {@code null} when a pot doesn't accept it. */
    @Nullable
    public static BlockState pottedFor(@Nullable ResourceLocation plantId) {
        if (plantId == null || plantId.equals(AIR_ID)) return null;
        Supplier<? extends Block> potted = plantMap().get(plantId);
        if (potted == null) return null;
        Block block = potted.get();
        return block == null || block == Blocks.FLOWER_POT ? null : block.defaultBlockState();
    }

    /** The plant id of {@code stack} when a pot accepts it, else {@code null} — the popup's "+ Held". */
    @Nullable
    public static ResourceLocation plantOf(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) return null;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());
        return pottedFor(id) == null ? null : id;
    }

    private static Map<ResourceLocation, Supplier<? extends Block>> plantMap() {
        return ((FlowerPotBlock) Blocks.FLOWER_POT).getFullPotsView();
    }
}
