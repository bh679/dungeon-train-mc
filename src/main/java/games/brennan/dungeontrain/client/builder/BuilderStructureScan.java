package games.brennan.dungeontrain.client.builder;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderBounds;
import games.brennan.dungeontrain.builder.structure.BuilderStructure;
import games.brennan.dungeontrain.builder.structure.BuilderStructureCells;
import games.brennan.dungeontrain.builder.structure.BuilderStructureNeeds;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the structures around the build to blocks, once a tick, for everything that needs them.
 *
 * <p>Two things do, and they want opposite halves of the same answer:
 * {@link BuilderStructureRenderer} draws them when they are ghosts, and
 * {@link OutOfBoundsWashRenderer} has to <em>not</em> paint them red when they are solid. Sweeping
 * once and handing each what it asked for is what keeps those two from disagreeing about where the
 * scenery is — which they would, since they would be asking on different frames.</p>
 *
 * <p><b>Not in the render pass.</b> Resolving a structure reads NBT through a synchronized store, so
 * this runs on a tick cadence and the consumers replay the result. The lists are replaced wholesale
 * rather than mutated, so a render pass can never see a partial sweep.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BuilderStructureScan {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int RESCAN_INTERVAL_TICKS = 5;

    /**
     * Hard ceiling on the cells held between sweeps.
     *
     * <p>A repeating room's full window is over a hundred copies of a thousand-cell room, which is
     * six figures of block models. {@link BuilderStructure.Detail#SHELL} is what keeps that down;
     * this is the backstop under it, and it is announced rather than silent — a scene with holes in
     * it reads as the build having holes rather than the sweep giving up.</p>
     */
    public static final int MAX_CELLS = 60_000;

    /** One resolved structure: its cells local to {@code origin}, and how opaque it draws. */
    public record Batch(Map<BlockPos, BlockState> cells, BlockPos origin, float fade) {

        public double distanceSqr(Vec3 cam) {
            double dx = origin.getX() - cam.x;
            double dy = origin.getY() - cam.y;
            double dz = origin.getZ() - cam.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private static volatile List<Batch> batches = List.of();
    private static volatile Set<BlockPos> solidCells = Set.of();

    private static int tickCounter = 0;
    private static volatile boolean loggedCap = false;

    private BuilderStructureScan() {}

    /** What to draw, when the structures are ghosts. Empty in every other state. */
    public static List<Batch> batches() {
        return batches;
    }

    /**
     * Whether a real block at this position is structure rather than something somebody left there.
     *
     * <p>Only ever true while the structures are solid — that is the one state in which they occupy
     * world positions at all. Always false otherwise, so the wash keeps painting stray blocks red.</p>
     */
    public static boolean isStructure(BlockPos pos) {
        return solidCells.contains(pos);
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        if (++tickCounter < RESCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        rescan();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        batches = List.of();
        solidCells = Set.of();
        loggedCap = false;
        // Template cells are only valid for the world that produced them.
        BuilderStructureCells.clear();
    }

    /**
     * Work out what is around the build, and what it is made of.
     *
     * <p>The declaration is computed rather than sent — see {@link BuilderBoundsState#structures()},
     * which is where the two sides agree.</p>
     */
    private static void rescan() {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        boolean draws = BuilderBoundsState.structureMode().draws();
        boolean stamps = BuilderBoundsState.structureMode().stamps();
        if (level == null || !(draws || stamps)) {
            batches = List.of();
            solidCells = Set.of();
            return;
        }
        List<BuilderStructure.Placement> placements = BuilderBoundsState.structures();
        if (placements.isEmpty()) {
            batches = List.of();
            solidCells = Set.of();
            return;
        }

        HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        BuilderStructureCells.Sources sources = sourcesFor(blocks);
        List<BoundingBox> volumes = BuilderBoundsState.volumes();

        List<Batch> found = new ArrayList<>(placements.size());
        Set<BlockPos> occupied = stamps ? new HashSet<>() : Set.of();
        int total = 0;
        boolean capped = false;

        for (BuilderStructure.Placement placement : placements) {
            Map<BlockPos, BlockState> cells = BuilderStructureCells.of(placement.kind(), sources);
            if (cells.isEmpty()) {
                continue;
            }
            // Anything inside the build, or in the courses the environment owns, is a real block
            // already — the same rule the materialiser writes through, so the two agree on which
            // cells are the structure's at all.
            Map<BlockPos, BlockState> visible = new LinkedHashMap<>();
            for (Map.Entry<BlockPos, BlockState> cell : cells.entrySet()) {
                if (total + visible.size() >= MAX_CELLS) {
                    capped = true;
                    break;
                }
                BlockPos world = placement.origin().offset(cell.getKey());
                if (!BuilderStructureNeeds.allows(placement.kind(), world, volumes)) {
                    continue;
                }
                if (stamps) {
                    occupied.add(world);
                } else {
                    visible.put(cell.getKey(), cell.getValue());
                }
            }
            if (!visible.isEmpty()) {
                found.add(new Batch(visible, placement.origin(), placement.fade()));
                total += visible.size();
            }
            if (capped) {
                break;
            }
        }

        if (capped && !loggedCap) {
            LOGGER.info("[DungeonTrain] Builder structures: hit the {}-cell sweep cap — the scene is "
                    + "resolved partially", MAX_CELLS);
            loggedCap = true;
        } else if (!capped) {
            loggedCap = false;
        }
        batches = List.copyOf(found);
        solidCells = stamps ? Set.copyOf(occupied) : Set.of();
    }

    /**
     * The resolver's inputs, from this side.
     *
     * <p>The seed comes off the integrated server because the generator keys its variant picks on it,
     * so a preview using a different one would show a line this world would never build. There is
     * always one: a builder world cannot be reached from a multiplayer client.</p>
     */
    private static BuilderStructureCells.Sources sourcesFor(HolderGetter<Block> blocks) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        long seed = server == null ? 0L : server.overworld().getSeed();
        List<BoundingBox> volumes = BuilderBoundsState.volumes();
        if (volumes.isEmpty()) {
            return new BuilderStructureCells.Sources(blocks, BuilderBoundsState.dims(), seed);
        }
        BoundingBox box = volumes.get(0);
        return new BuilderStructureCells.Sources(blocks, BuilderBoundsState.dims(), seed,
                liveCells(box), BuilderBounds.sizeOf(box));
    }

    /**
     * The open build's blocks as they are right now, local to its origin.
     *
     * <p>Only the room kinds read this, and it is why a room's copies update as you place blocks in
     * it — the one thing a preview must never get wrong is quietly disagreeing with the work in front
     * of you.</p>
     */
    private static Map<BlockPos, BlockState> liveCells(BoundingBox box) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return Map.of();
        }
        Vec3i size = BuilderBounds.sizeOf(box);
        BlockPos min = BuilderBounds.originOf(box);
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int y = 0; y < size.getY(); y++) {
            for (int x = 0; x < size.getX(); x++) {
                for (int z = 0; z < size.getZ(); z++) {
                    BlockState state = level.getBlockState(
                            probe.set(min.getX() + x, min.getY() + y, min.getZ() + z));
                    if (!state.isAir()) {
                        out.put(new BlockPos(x, y, z), state);
                    }
                    if (out.size() >= MAX_CELLS) {
                        return out;
                    }
                }
            }
        }
        return out;
    }
}
