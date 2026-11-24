package games.brennan.dungeontrain.block;

import games.brennan.dungeontrain.registry.ModBlocks;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The live-run rule for {@link ForcedAirBlock}: wherever a template or a variant pick puts Forced
 * Air, the world gets plain {@code minecraft:air}, replacing water, terrain or anything else there.
 *
 * <p>One rule, called from every stamp path, so the block never reaches a live world as itself.</p>
 */
public final class ForcedAir {

    private ForcedAir() {}

    /** True for a Forced Air state. Null-safe, and false before the registry is populated. */
    public static boolean is(BlockState state) {
        return state != null && ModBlocks.FORCED_AIR.isBound() && state.is(ModBlocks.FORCED_AIR.get());
    }

    /**
     * {@code minecraft:air} for Forced Air in a live stamp, otherwise {@code state} unchanged.
     *
     * <p>Live means inside a {@link games.brennan.dungeontrain.train.StagePlacementScope} — the same
     * signal the stage and variant placeholders resolve on. Every editor stamp runs unscoped, because
     * its plot is captured back into the template on save, so Forced Air stays itself there.</p>
     */
    public static BlockState forStamp(BlockState state) {
        return forStamp(state, games.brennan.dungeontrain.train.StagePlacementScope.active());
    }

    /** {@link #forStamp(BlockState)} with whether the stamp is live given. */
    public static BlockState forStamp(BlockState state, boolean live) {
        if (!ModBlocks.FORCED_AIR.isBound()) return state;
        return forStamp(state, live, ModBlocks.FORCED_AIR.get());
    }

    /**
     * The rule itself, with the block it applies to given — what {@link #forStamp(BlockState, boolean)}
     * runs with the registered Forced Air, and what a unit test runs with a stand-in (the mod's
     * registry is never populated under test).
     */
    static BlockState forStamp(BlockState state, boolean live, net.minecraft.world.level.block.Block forcedAir) {
        return live && state != null && state.is(forcedAir) ? Blocks.AIR.defaultBlockState() : state;
    }

    /** True when {@code state} is Forced Air being stamped live — to be written as air. */
    public static boolean writesAir(BlockState state) {
        return forStamp(state) != state;
    }
}
