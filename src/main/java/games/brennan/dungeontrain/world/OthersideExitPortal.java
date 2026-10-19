package games.brennan.dungeontrain.world;

import com.kyanite.deeperdarker.content.blocks.OthersidePortalBlock;
import com.kyanite.deeperdarker.world.otherside.OthersideDimension;
import com.kyanite.deeperdarker.world.otherside.OthersideTeleporter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.DimensionTransition;
import org.jetbrains.annotations.Nullable;

/**
 * The exit end of an Otherside portal trip along the ride ({@code event/NetherPortalBandJump},
 * {@code Kind.OTHERSIDE}): an Otherside portal already standing near the exit is used as it is, otherwise one is
 * built <em>at</em> the asked-for spot — a reinforced-deepslate frame on a small platform, its 2×3 opening
 * cleared — and the trip lands in it.
 *
 * <p>Built here rather than by Deeper Darker's own {@link OthersideTeleporter#makePortal}, which takes the first
 * valid spot scanning down from the build limit: under a Deeper Dark core's bedrock roof, or the mountains
 * around it, that is the surface far above the track. The transition itself is still Deeper Darker's
 * ({@link OthersideTeleporter#getExitPortal}), which finds the frame just built — the nearest one — and keeps
 * the mod's own arrival sound and chunk ticket.</p>
 *
 * <p>Every Deeper Darker member used here is listed in {@code compat/mixinguard/ThirdPartyMixinTargets} and reached
 * only through {@code NetherPortalBandJump#othersideDestination}, which turns a {@link LinkageError} from a newer
 * Deeper Darker build into the mod's own portal behaviour.</p>
 */
public final class OthersideExitPortal {

    /** How far around the exit an existing Otherside portal is reused (Deeper Darker's own search radius). */
    private static final int REUSE_RADIUS = 128;

    private OthersideExitPortal() {}

    /** {@code NetherPortalBandJump.ExitPortalFinder} for Otherside portals ({@code isNether} is unused). */
    @Nullable
    public static DimensionTransition find(ServerLevel level, Entity entity, BlockPos portalPos, BlockPos exitPos,
                                           boolean isNether, WorldBorder border) {
        BlockPos exit = border.clampToBounds(exitPos.getX(), exitPos.getY(), exitPos.getZ());
        if (!hasPortalNear(level, exit)) build(level, exit);
        return OthersideTeleporter.getExitPortal(level, entity, portalPos, exit, border);
    }

    private static boolean hasPortalNear(ServerLevel level, BlockPos exit) {
        PoiManager poi = level.getPoiManager();
        poi.ensureLoadedAndValid(level, exit, REUSE_RADIUS);
        return poi.getInSquare(type -> type.is(OthersideDimension.OTHERSIDE_PORTAL.getKey()), exit, REUSE_RADIUS,
                PoiManager.Occupancy.ANY).findAny().isPresent();
    }

    /**
     * A frame along X at {@code exit}: opening {@code x..x+1} × {@code y..y+2}, frame one block around it, a
     * 4×3 floor under it and the air on both faces cleared, so the player always steps out onto solid ground.
     */
    static void build(ServerLevel level, BlockPos exit) {
        int x = exit.getX();
        int z = exit.getZ();
        int y = Math.max(level.getMinBuildHeight() + 2, Math.min(exit.getY(), level.getMaxBuildHeight() - 6));
        level.getChunk(x >> 4, z >> 4);
        BlockState frame = Blocks.REINFORCED_DEEPSLATE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 2; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(p.set(x + dx, y - 1, z + dz), frame, Block.UPDATE_CLIENTS);
                for (int dy = 0; dy <= 3; dy++) {
                    boolean frameCell = dz == 0 && (dx == -1 || dx == 2 || dy == 3);
                    level.setBlock(p.set(x + dx, y + dy, z + dz), frameCell ? frame : air, Block.UPDATE_CLIENTS);
                }
            }
        }
        Block portalBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath(games.brennan.dungeontrain.worldgen.OthersideCore.NAMESPACE,
                        "otherside_portal"));
        BlockState portal = portalBlock.defaultBlockState().setValue(OthersidePortalBlock.AXIS, Direction.Axis.X);
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                level.setBlock(p.set(x + dx, y + dy, z), portal, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
    }
}
