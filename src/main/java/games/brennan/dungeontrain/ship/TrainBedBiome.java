package games.brennan.dungeontrain.ship;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import games.brennan.dungeontrain.util.LogFirstN;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import org.joml.Vector3d;
import org.slf4j.Logger;

/**
 * Resolves the biome at a bed's <b>real worldspace position</b> for the bed-explosion decision.
 *
 * <p>Train carriages are Sable sub-levels: their blocks live in a far-away plot/storage region
 * (~2.048e7 on X) and are only <em>rendered and collided</em> at the train's world position (see
 * {@link TrainFluidBarrier} / {@code FlowingFluidExternalWaterMixin}). A bed placed on a carriage
 * therefore has a {@link BlockPos} in that plot region; sampling {@code level.getBiome(pos)} there
 * reads whatever biome the plot coordinates happen to bake — which can be an {@code IS_NETHER}/
 * {@code IS_END} band biome — and detonates the bed even though the train is visibly in ordinary
 * overworld terrain. We instead transform the bed's sub-level position into worldspace via the
 * carriage's {@code logicalPose()} (the same plot→world transform {@link ManagedShip#shipToWorld}
 * uses) and sample the host overworld's biome <em>where the train actually is</em>, so the
 * explosion follows the world the player sees.</p>
 *
 * <p>Plain (non-mixin) class on purpose: it references JOML and Sable types that are not visible to
 * the mixin transformer's bootstrap classloader — mirrors {@link TrainFluidBarrier}. Any lookup or
 * transform error falls back to the raw {@code level.getBiome(pos)} so bed use is never broken.</p>
 */
public final class TrainBedBiome {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN TRANSFORM_ERRORS = new LogFirstN(5);

    private TrainBedBiome() {}

    /**
     * The biome at {@code pos}'s worldspace location. For a bed on the real ground this is just
     * {@code level.getBiome(pos)}; for a bed inside a Sable carriage sub-level it is the host
     * overworld biome at the carriage's rendered world position.
     */
    public static Holder<Biome> worldspaceBiomeAt(ServerLevel level, BlockPos pos) {
        try {
            SubLevel sub = Sable.HELPER.getContaining(level, pos); // null => not inside any carriage
            if (sub instanceof ServerSubLevel ssl) {
                Vector3d world = ssl.logicalPose().transformPosition(
                        new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
                ServerLevel overworld = level.getServer().overworld();
                return overworld.getBiome(BlockPos.containing(world.x, world.y, world.z));
            }
        } catch (Throwable t) {
            TRANSFORM_ERRORS.error(LOGGER,
                    "[DungeonTrain] Bed worldspace biome transform failed; using raw plot biome instead", t);
        }
        return level.getBiome(pos);
    }
}
