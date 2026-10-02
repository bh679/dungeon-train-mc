package games.brennan.dungeontrain.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.betterx.bclib.sdf.SDF;
import org.betterx.betterend.noise.OpenSimplexNoise;

import java.util.function.Supplier;

/**
 * One thread's placement state for BetterEnd's {@code BiomeIslandFeature}, which keeps the island's centre,
 * noise and blocks in statics that its shared SDF reads while filling
 * (see {@code mixin/betterend/BetterEndBiomeIslandPerThreadMixin}).
 */
public final class BiomeIslandState {

    private static final long DEFAULT_NOISE_SEED = 412L;

    private static final ThreadLocal<BiomeIslandState> STATE = ThreadLocal.withInitial(BiomeIslandState::new);

    public final BlockPos.MutableBlockPos center = new BlockPos.MutableBlockPos();
    public OpenSimplexNoise noise = new OpenSimplexNoise(DEFAULT_NOISE_SEED);
    public BlockState topBlock = defaultTopBlock();
    public BlockState underBlock = Blocks.DIRT.defaultBlockState();
    private SDF island;

    private BiomeIslandState() {}

    public static BiomeIslandState get() {
        return STATE.get();
    }

    /** The top block an island gets unless its placement picks another. */
    public static BlockState defaultTopBlock() {
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    /** This thread's island shape, built on first use. */
    public SDF island(Supplier<SDF> factory) {
        if (island == null) island = factory.get();
        return island;
    }
}
