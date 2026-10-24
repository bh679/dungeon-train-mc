package games.brennan.dungeontrain.compat.vista;

import games.brennan.dungeontrain.item.LiveCassetteItem;
import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Recognises a Vista Television by block id, without touching a Vista class, so code outside the
 * Vista mixin set can ask safely.
 *
 * <p>A TV's block entity is Moonlight's {@code ItemDisplayTile}, a {@code RandomizableContainerBlockEntity}
 * with one slot. That slot is the TV's tuner (it holds the Live Feed Cassette), never storage: DT's loot
 * roller and break spill both skip a TV on this test.</p>
 */
public final class TvBlocks {

    static final ResourceLocation TV_ID = ResourceLocation.fromNamespaceAndPath("vista", "television");

    private TvBlocks() {}

    /** Id-level rule, split out so it is testable without a bootstrapped registry. */
    static boolean isTelevision(ResourceLocation id) {
        return TV_ID.equals(id);
    }

    public static boolean isTelevision(BlockState state) {
        return state != null && isTelevision(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    /** Either Live Feed Cassette: the first stream's or the second stream's (Two Streams). */
    public static boolean isLiveCassette(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof LiveCassetteItem;
    }

    /** The second stream's cassette specifically. */
    public static boolean isSecondCassette(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof LiveCassetteItem c && c.isSecond();
    }

    /** A fresh cassette for the first stream ({@code second} false) or the second. */
    public static ItemStack liveCassette(boolean second) {
        return new ItemStack(second ? ModItems.LIVE_CASSETTE_2.get() : ModItems.LIVE_CASSETTE.get());
    }

    /** The cassette the TV switches to from {@code held}: a second-stream cassette goes back to the first, anything else to the second. */
    public static ItemStack otherCassette(ItemStack held) {
        return liveCassette(!isSecondCassette(held));
    }
}
