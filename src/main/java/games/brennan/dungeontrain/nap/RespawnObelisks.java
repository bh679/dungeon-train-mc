package games.brennan.dungeontrain.nap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * BetterEnd's Respawn Obelisk as the End's nap spot, the End's counterpart of the respawn anchor: it
 * offers a nap in an End core or anywhere in the End band, its crumbling strip included, and explodes
 * anywhere else ({@link NapBedEvents}). Known by registry id
 * and its {@code shape} property read by name, so DT needs no compile dependency on BetterEnd.
 *
 * <p>An obelisk is three blocks tall: {@code shape} bottom, middle, top.</p>
 */
public final class RespawnObelisks {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("betterend", "respawn_obelisk");
    /** BetterEnd's Amber Gem, what an obelisk is fed ({@link RespawnObeliskGems}). */
    public static final ResourceLocation AMBER_GEM = ResourceLocation.fromNamespaceAndPath("betterend", "amber_gem");
    private static final String SHAPE = "shape";
    private static final int HEIGHT = 3;

    private RespawnObelisks() {}

    public static boolean isObelisk(BlockState state) {
        return ID.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    public static boolean isAmberGem(ItemStack stack) {
        return !stack.isEmpty() && AMBER_GEM.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /** True for the obelisk's bottom part, the one its gems are kept under. */
    public static boolean isBottom(BlockState state) {
        return isObelisk(state) && partIndex(state) == 0;
    }

    /** Index of {@code state}'s part from the bottom: 0 bottom, 1 middle, 2 top (0 when unreadable). */
    static int partIndex(BlockState state) {
        for (Property<?> p : state.getProperties()) {
            if (!p.getName().equals(SHAPE)) continue;
            return partIndex(state.getValue(p).toString());
        }
        return 0;
    }

    /** {@code "bottom"} 0, {@code "middle"} 1, {@code "top"} 2, anything else 0. Pure, for tests. */
    static int partIndex(String shape) {
        return switch (shape.toLowerCase(java.util.Locale.ROOT)) {
            case "middle" -> 1;
            case "top" -> 2;
            default -> 0;
        };
    }

    /** The obelisk's bottom block, from any of its parts. */
    public static BlockPos bottom(BlockPos pos, BlockState state) {
        return pos.below(partIndex(state));
    }

    /** All three of the obelisk's positions, bottom first. */
    public static List<BlockPos> parts(BlockPos pos, BlockState state) {
        BlockPos bottom = bottom(pos, state);
        List<BlockPos> out = new ArrayList<>(HEIGHT);
        for (int i = 0; i < HEIGHT; i++) out.add(bottom.above(i));
        return out;
    }

    /**
     * Blows the obelisk up the way a bed or anchor goes outside its home dimension: every part removed,
     * then a power-5 fiery explosion at the middle.
     */
    public static void explode(ServerLevel level, BlockPos pos, BlockState state) {
        List<BlockPos> parts = parts(pos, state);
        RespawnObeliskGems.get(level).forget(parts.get(0));
        for (BlockPos p : parts) {
            if (isObelisk(level.getBlockState(p))) level.removeBlock(p, false);
        }
        Vec3 center = parts.get(1).getCenter();
        level.explode(null, level.damageSources().badRespawnPointExplosion(center), null, center, 5.0F, true,
            Level.ExplosionInteraction.BLOCK);
    }
}
