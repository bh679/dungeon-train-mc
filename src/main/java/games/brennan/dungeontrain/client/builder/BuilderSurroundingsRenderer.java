package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderGhostMode;
import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderNewOptions;
import games.brennan.dungeontrain.builder.BuilderSurroundings;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Draws what {@link BuilderSurroundings} says is around the build.
 *
 * <p>The showing half of the pair. The declaration answers "what goes here"; this answers "so what
 * does that look like", and the two never argue because neither is allowed an opinion about the
 * other's question.</p>
 *
 * <p>What the scenery control means, uniformly in every mode — the uniformity being the point, since
 * it used to mean something different in each and nothing at all in two of them:</p>
 *
 * <table border="1">
 *   <caption>Scenery states</caption>
 *   <tr><th></th><th>Real blocks?</th><th>Drawn here?</th></tr>
 *   <tr><td>{@link BuilderGhostMode#GHOST}</td><td>no</td><td>translucent</td></tr>
 *   <tr><td>{@link BuilderGhostMode#NONE}</td><td>no</td><td>no</td></tr>
 *   <tr><td>{@link BuilderGhostMode#SOLID}</td><td>yes — stood up server-side</td><td>no need</td></tr>
 * </table>
 *
 * <p><b>Drawn, never stamped.</b> Nothing in this class places a block. That is what keeps the
 * surroundings out of the saved template, out of the dirty check, and out of reach of the block
 * protection rules — standing them up is the server's job and is driven by the same declaration
 * ({@code BuilderSurroundingsBuild}), so the two can't diverge about what belongs.</p>
 *
 * <p>The sweep runs on a tick cadence and the render pass replays it, the shape every builder ghost
 * renderer here uses: resolving a template reads NBT through a synchronized store, which is not a
 * thing to do per frame.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BuilderSurroundingsRenderer {

    private static final int RESCAN_INTERVAL_TICKS = 10;

    /** Hard ceiling on cells held for a frame. Matches the other builder renderers'. */
    private static final int MAX_CELLS = 40_000;

    /**
     * Half opacity — solid enough to read the material, faint enough never to be mistaken for one.
     *
     * <p>Scaled by each surround's own fade, which is how a room's outer rings thin out toward the
     * edge of the window instead of stopping at a hard line the real space does not have.</p>
     */
    private static final float ALPHA = 0.5F;

    /** One resolved surround: cells, where they go, and how opaque. */
    private record Batch(Map<BlockPos, BlockState> cells, BlockPos origin, float alpha) {

        double distanceSqr(Vec3 cam) {
            double dx = origin.getX() - cam.x;
            double dy = origin.getY() - cam.y;
            double dz = origin.getZ() - cam.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /** Replaced wholesale, so a render pass never sees a partial sweep. */
    private static volatile List<Batch> batches = List.of();

    private static int tickCounter = 0;

    private BuilderSurroundingsRenderer() {}

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
        // Template cells are only valid for the world that produced them.
        SurroundCells.clear();
    }

    /**
     * Work out what is around the build, and what it is made of.
     *
     * <p>The declaration is computed here from what the client already knows rather than sent: the
     * mode, the sub type, the name and the volumes all arrive on {@code BuilderBoundsPacket}
     * already, and both sides running the same function is what makes them agree without a packet to
     * keep in step.</p>
     */
    private static void rescan() {
        Minecraft mc = Minecraft.getInstance();
        List<BoundingBox> volumes = BuilderBoundsState.volumes();
        if (volumes.isEmpty() || mc.level == null
                || BuilderGhostCellsState.mode() != BuilderGhostMode.GHOST) {
            batches = List.of();
            return;
        }

        BuilderSurroundings.Context ctx = context(volumes);
        if (ctx == null) {
            batches = List.of();
            return;
        }

        List<Batch> found = new ArrayList<>();
        int cells = 0;
        for (BuilderSurroundings.Surround s : BuilderSurroundings.around(ctx)) {
            Map<BlockPos, BlockState> resolved = cellsFor(s, ctx);
            if (resolved.isEmpty()) {
                continue;
            }
            found.add(new Batch(resolved, s.origin(), ALPHA * s.fade()));
            cells += resolved.size();
            if (cells >= MAX_CELLS) {
                break;
            }
        }
        batches = List.copyOf(found);
    }

    /**
     * The blocks of one surround, or empty for one this renderer does not draw yet.
     *
     * <p><b>Three sources are declared but still drawn by the renderer that already drew them</b>,
     * and deliberately so — each is currently <em>real blocks in the world</em>, and drawing a ghost
     * over a real block shows it twice:</p>
     * <ul>
     *   <li>{@link BuilderSurroundings.Source.TrackCorridor} — {@code applyScenery} lays the line for
     *       real, and #973 landed precisely to make sure it does. Ghosting it means first not
     *       standing it up, which is the materialiser's half of this work;</li>
     *   <li>{@link BuilderSurroundings.Source.TrackScene} — {@link BuilderTrackGhostRenderer} draws
     *       it, composed against the open plot with the live plot's own cells, which is more than a
     *       source lookup can answer;</li>
     *   <li>the room sources — {@link BuilderRoomGhostRenderer} draws those, and its culling lookup
     *       wraps around the tile grid so the seam between two copies is culled as the interior it
     *       is. That is a property of the grid, not of a source.</li>
     * </ul>
     *
     * <p>They are declared anyway, because the declaration is the description of the scene and
     * leaving holes in it to match today's renderers would make it a description of the renderers
     * instead.</p>
     */
    private static Map<BlockPos, BlockState> cellsFor(BuilderSurroundings.Surround s,
                                                      BuilderSurroundings.Context ctx) {
        return switch (s.source()) {
            case BuilderSurroundings.Source.Carriage ignored -> SurroundCells.of(s.source(), ctx.dims());
            case BuilderSurroundings.Source.CarriageSkin ignored -> SurroundCells.of(s.source(), ctx.dims());
            case BuilderSurroundings.Source.FlatbedPad ignored -> SurroundCells.of(s.source(), ctx.dims());
            case BuilderSurroundings.Source.TrackCorridor ignored -> Map.of();
            case BuilderSurroundings.Source.TrackScene ignored -> Map.of();
            case BuilderSurroundings.Source.RoomTile ignored -> Map.of();
            case BuilderSurroundings.Source.RoomShell ignored -> Map.of();
        };
    }

    /** Everything the declaration needs, from what the client was already told. */
    private static BuilderSurroundings.Context context(List<BoundingBox> volumes) {
        BuilderMode mode = BuilderMode.fromId(BuilderBoundsState.modeId()).orElse(null);
        if (mode == null) {
            return null;
        }
        BoundingBox first = volumes.get(0);
        // The build volume is one carriage, so its own extents are this world's CarriageDims — no
        // need to be told them, and no way for the two to disagree.
        CarriageDims dims = CarriageDims.clamp(
                first.getXSpan(), first.getZSpan(), first.getYSpan());
        TrackKind trackKind = TrackKind.fromId(BuilderBoundsState.trackKindId());

        return new BuilderSurroundings.Context(mode,
                BuilderNewOptions.SubType.fromId(BuilderBoundsState.subTypeId()).orElse(null),
                BuilderBoundsState.buildName(), dims, volumes.size(),
                // Rooms keep their own renderer for now: its scene is a tile grid with a wrapped
                // culling lookup, which is a different question from "what goes here".
                null, null,
                trackKind, BuilderBoundsState.buildName());
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        List<Batch> snapshot = batches;
        if (snapshot.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return;
        }

        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffer.getBuffer(BuilderGhostCells.GHOST);
        BlockRenderDispatcher blocks = mc.getBlockRenderer();
        RandomSource random = RandomSource.create();

        // Nearest first: the shared batch writes depth, so whichever fragment lands first wins the
        // pixel, and that is only the answer you want if the nearest one got there first. Sorted by
        // batch rather than by cell — a handful of comparisons instead of tens of thousands.
        List<Batch> ordered = new ArrayList<>(snapshot);
        ordered.sort(Comparator.comparingDouble(b -> b.distanceSqr(cam)));

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        for (Batch batch : ordered) {
            BuilderGhostCells.draw(ps, vc, blocks, level, random, batch.cells(), batch.cells(),
                    UnaryOperator.identity(), batch.origin(), batch.alpha());
        }
        ps.popPose();
        buffer.endBatch(BuilderGhostCells.GHOST);
    }
}
