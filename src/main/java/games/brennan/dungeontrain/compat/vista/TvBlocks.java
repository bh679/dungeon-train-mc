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

    /** The Tribute Cassette: the one a tribute room's TV is tuned with ({@code TributeTvBroadcast}). */
    public static boolean isTributeCassette(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof games.brennan.dungeontrain.item.TributeTvCassetteItem;
    }

    /** A fresh Tribute Cassette. */
    public static ItemStack tributeCassette() {
        return new ItemStack(ModItems.TRIBUTE_CASSETTE.get());
    }

    /**
     * Tune every TV in the box to the Tribute Cassette, whatever its slot holds. A tribute room's TV is
     * the one thing in it that is never the owner's to retune: a room saved with the slot empty was
     * once handed a Live Feed Cassette by the load backstop ({@code TVBlockEntityMixin}) and kept it,
     * and the visit then opened on the stream instead of the owner's own story. Returns how many were retuned.
     */
    public static int tuneTributeTvs(net.minecraft.server.level.ServerLevel level, net.minecraft.world.level.levelgen.structure.BoundingBox box) {
        int tuned = 0;
        for (net.minecraft.core.BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            if (!isTelevision(level.getBlockState(pos))) continue;
            if (!(level.getBlockEntity(pos) instanceof net.mehvahdjukaar.vista.common.tv.TVBlockEntity tv)) continue;
            if (isTributeCassette(tv.getItem(0))) continue;
            tv.setItem(0, tributeCassette());
            tv.setChanged();
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            level.sendBlockUpdated(pos.immutable(), state, state, 3);
            tuned++;
        }
        return tuned;
    }

    /** A fresh cassette for the first stream ({@code second} false) or the second. */
    public static ItemStack liveCassette(boolean second) {
        return new ItemStack(second ? ModItems.LIVE_CASSETTE_2.get() : ModItems.LIVE_CASSETTE.get());
    }

}
