package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderGhostSlots;
import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Draws the rest of the train as translucent ghosts around the carriage being edited.
 *
 * <p>Opening a carriage room or a part parks a single carriage — one template is one carriage — but
 * a carriage on its own doesn't tell you how it reads in a run. The rest of the group is drawn back
 * in: the empty carriage slots either side, the flatbed pads that cap it, and — when you are
 * authoring a room from inside — the shell of the carriage you are standing in, which
 * {@code BuilderGhostCells} lifted out of the world so there is something to see through.</p>
 *
 * <p><b>Textured, from each cell's own block model</b> — {@link BuilderGhostCells}, the tool the
 * track and room ghosts already draw with. This used to emit flat quads over each exposed face at a
 * tenth opacity, and a silhouette is the wrong answer to the question being asked here: what a
 * builder is judging from the middle of a train is how a real carriage reads against real ones, and
 * an abstract shape hanging in the air tells them nothing about it.</p>
 *
 * <p><b>Drawn, never stamped.</b> No block is placed for a ghost. That is what keeps them out of the
 * saved template, out of the dirty check, and out of reach of the block protection rules — the
 * alternative (stamp them and forbid editing) would have had all three of those to get right, and
 * any bug in any of them writes scenery into somebody's carriage.</p>
 *
 * <p>The carriage geometry mostly comes free: a ghost carriage is a copy of the one already standing
 * in the world, so the sweep reads the parked volume out of the client's own level. What it cannot
 * read is the part that isn't there — a lifted shell, and pads a one-carriage world never stamps —
 * and that arrives on {@code BuilderGhostCellsPacket}. The two are unioned before drawing, so a
 * neighbouring slot still shows a whole carriage rather than a room with no walls.</p>
 *
 * <p>Deliberately the same shape as {@link OutOfBoundsWashRenderer} beside it — a cadenced sweep
 * caching what to draw, a pass after translucent blocks — and for the same reason: real transparency
 * would mean forcing blocks into the translucent chunk layer from a meshing hook, and Sodium
 * replaces that meshing entirely, so the effect would silently vanish for anyone running it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BuilderGhostTrainRenderer {

    private static final int RESCAN_INTERVAL_TICKS = 10;
    /** Hard ceiling so a pathological build can't stall a frame. Matches the other two renderers'. */
    private static final int MAX_CELLS = 40_000;

    /**
     * Half opacity, the track ghosts' value.
     *
     * <p>Was a tenth while these were untextured silhouettes, where the only job was to suggest an
     * outline. A block model at a tenth is not faint, it is invisible — there is a texture under it
     * now, and the whole point is to be able to read the material.</p>
     */
    private static final float ALPHA = 0.5F;

    /**
     * Cells of the parked carriage in <b>local</b> coordinates, so one sweep serves every slot.
     * Replaced wholesale, so a render pass never sees a partial sweep.
     */
    private static volatile Map<BlockPos, BlockState> carriage = Map.of();
    /** World position the local cells are measured from, and the slots to repeat them at. */
    private static volatile BlockPos origin = BlockPos.ZERO;
    private static volatile BuilderGhostSlots.Ghosts ghosts =
            new BuilderGhostSlots.Ghosts(List.of(), List.of(), 0);
    /** Whether the carriage on the track has had its shell lifted, and so needs it drawn back. */
    private static volatile boolean shellLifted = false;
    /**
     * Whether to draw the <em>rest of the train</em> — the empty slots and the pads.
     *
     * <p>Separate from {@link #shellLifted} because the display toggle governs one and not the
     * other. "Show the builder's ghosts" is a question about context you can take or leave; the
     * shell of the carriage you are standing in is not context, it is the carriage, and it has been
     * lifted out of the world. Switching the toggle off must not leave a room with no walls and
     * nothing drawn where they were.</p>
     */
    private static volatile boolean drawSurroundings = false;

    private static int tickCounter = 0;

    private BuilderGhostTrainRenderer() {}

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
        clear();
        // The next world sends its own; a kept entry would ghost one build's shell into another's.
        BuilderGhostCellsState.clear();
    }

    private static void clear() {
        carriage = Map.of();
        ghosts = new BuilderGhostSlots.Ghosts(List.of(), List.of(), 0);
        shellLifted = false;
        drawSurroundings = false;
    }

    /**
     * Work out which slots are empty, then cache what a carriage in one looks like.
     *
     * <p>Both halves of "which slots" are already on the client: {@code volumes.size()} is what is
     * parked, and the mode id says what a whole carriage would have parked. No new packet field, and
     * no third place for the two to disagree.</p>
     */
    private static void rescan() {
        Minecraft mc = Minecraft.getInstance();
        List<BoundingBox> volumes = BuilderBoundsState.volumes();
        Map<BlockPos, BlockState> shell = BuilderGhostCellsState.shell();
        boolean surroundings = ClientDisplayConfig.isBuilderGhostTrainEnabled();
        // With the toggle off there is still the lifted shell to put back — see drawSurroundings.
        if (volumes.isEmpty() || mc.level == null || (!surroundings && shell.isEmpty())) {
            clear();
            return;
        }

        BoundingBox parked = volumes.get(0);
        // How much train to draw, not how much is parked — Inside Carriage parks one by definition,
        // so reading the parked count here left it with no slots to fill and no ghosts at all.
        int full = BuilderWorldLayout.ghostGroupCarriages(
                BuilderMode.fromId(BuilderBoundsState.modeId()).orElse(null));
        // The build volume is one carriage, so its own extents are this world's CarriageDims —
        // no need to be told them, and no way for the two to disagree.
        int length = parked.maxX() - parked.minX() + 1;
        int width = parked.maxZ() - parked.minZ() + 1;
        int height = parked.maxY() - parked.minY() + 1;
        CarriageDims dims = new CarriageDims(length, width, height);
        BuilderGhostSlots.Ghosts slots = BuilderGhostSlots.of(parked.minX(), volumes.size(), full,
                length, CarriagePlacer.halfPadLen(dims));
        if (slots.isEmpty() && shell.isEmpty()) {
            clear();
            return;
        }

        Level level = mc.level;
        BlockPos min = new BlockPos(parked.minX(), parked.minY(), parked.minZ());
        // The shell first, so a real block in the world always wins the cell: the lifted copy is
        // what the carriage looked like when it was stamped, and the sweep is what it looks like
        // now. Where they disagree, now is right.
        Map<BlockPos, BlockState> found = new LinkedHashMap<>(shell);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        outer:
        for (int x = parked.minX(); x <= parked.maxX(); x++) {
            for (int y = parked.minY(); y <= parked.maxY(); y++) {
                for (int z = parked.minZ(); z <= parked.maxZ(); z++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    found.put(new BlockPos(x - min.getX(), y - min.getY(), z - min.getZ()), state);
                    if (found.size() >= MAX_CELLS) {
                        break outer;
                    }
                }
            }
        }

        origin = min;
        ghosts = slots;
        shellLifted = !shell.isEmpty();
        drawSurroundings = surroundings;
        carriage = found;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Map<BlockPos, BlockState> cells = carriage;
        Map<BlockPos, BlockState> shell = BuilderGhostCellsState.shell();
        Map<BlockPos, BlockState> backPad = BuilderGhostCellsState.backPad();
        Map<BlockPos, BlockState> frontPad = BuilderGhostCellsState.frontPad();
        BuilderGhostSlots.Ghosts slots = ghosts;
        boolean surroundings = drawSurroundings;
        if (cells.isEmpty() || (!shellLifted && (slots.isEmpty() || !surroundings))) {
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
        BlockPos base = origin;

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);

        // Nearest batch first: the shared batch writes depth, so whichever fragment lands first wins
        // the pixel, and that is only the answer you want if the nearest one got there first. Sorted
        // by slot rather than by cell — a handful of comparisons instead of tens of thousands.
        List<Batch> batches = new ArrayList<>();
        // The shell of the carriage on the track, put back where it was lifted from. Only this one:
        // the empty slots get it as part of `cells`, and drawing it twice at the parked slot would
        // double the blend and read as a darker carriage than its neighbours.
        if (shellLifted) {
            batches.add(new Batch(shell, cells, base));
        }
        if (surroundings) {
            for (int slotX : slots.carriageMinX()) {
                batches.add(new Batch(cells, cells, new BlockPos(slotX, base.getY(), base.getZ())));
            }
            // The flatbed pads capping the group, sitting on the train floor like the real ones. The
            // pads are listed low-X first, which is the BACK one — the order they were captured in.
            List<Integer> padXs = slots.padMinX();
            for (int i = 0; i < padXs.size(); i++) {
                Map<BlockPos, BlockState> pad = i == 0 ? backPad : frontPad;
                if (!pad.isEmpty()) {
                    batches.add(new Batch(pad, pad,
                            new BlockPos(padXs.get(i), BuilderWorldLayout.TRAIN_Y, base.getZ())));
                }
            }
        }
        batches.sort(Comparator.comparingDouble(b -> b.distanceSqr(cam)));

        for (Batch batch : batches) {
            BuilderGhostCells.draw(ps, vc, blocks, level, random, batch.draw(), batch.neighbours(),
                    UnaryOperator.identity(), batch.offset(), ALPHA);
        }

        ps.popPose();
        buffer.endBatch(BuilderGhostCells.GHOST);
    }

    /**
     * One repeat of one shape, at one place.
     *
     * @param neighbours what the face-culling test consults — the whole carriage even when
     *                   {@code draw} is only its shell, so the shell's inward faces stay culled
     *                   against the contents standing behind them
     */
    private record Batch(Map<BlockPos, BlockState> draw, Map<BlockPos, BlockState> neighbours,
                         BlockPos offset) {

        /** Rough distance from the camera to this batch's origin, for the front-to-back ordering. */
        double distanceSqr(Vec3 cam) {
            double dx = offset.getX() - cam.x;
            double dy = offset.getY() - cam.y;
            double dz = offset.getZ() - cam.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
