package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.EditorDoorGhostsPacket;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;

/**
 * World-space overlay that stands a translucent oak door in each portal corridor doorway at a
 * portal-room editor plot — the openings an author has to build the room around.
 *
 * <p><b>The door itself, not a marker shaped like one.</b> The state rendered is
 * {@link PortalCarriageBuilder#doorState}, the very state the builder hangs in a real corridor, so
 * the ghost shows the actual facing and hinge an author will meet rather than a lookalike that could
 * drift from it. A plain coloured cube said "something is here"; a door says <i>what</i>, which is
 * the whole question when the thing being authored is the room it opens into.</p>
 *
 * <p><b>Translucent, so it still reads as a ghost.</b> The model is pushed through
 * {@link GhostBuffer}, which caps every vertex's alpha and forces the whole model onto
 * {@link RenderType#translucent()} — a door rendered opaque would be indistinguishable from one an
 * author had actually placed, which is exactly the confusion the ghosts exist to prevent. A
 * wireframe boxes each half for the same reason {@link EditorStrayGhostRenderer} outlines its
 * strays: against a dark room wall a translucent model alone is easy to miss from across the plot.
 * Never the strays' red, because these two overlays say opposite things — red means "remove this",
 * these say "leave this alone".</p>
 *
 * <h2>Which mouth is which — named and coloured</h2>
 * <p>A room's two doorways are separately authored: they can sit at different offsets and heights,
 * and {@code PortalRoomDoorPointer} decides which one a right-click edits purely from the column it
 * landed on. Two identical amber boxes therefore left an author unable to tell, before clicking,
 * which end they were aiming at. Each ghost now carries a floating <b>Entrance</b> / <b>Exit</b>
 * label, and the entry mouth is drawn blue against the exit's yellow.</p>
 *
 * <p>The label and the outline take the <i>same</i> colour deliberately. Text stops being legible
 * long before a wireframe stops being visible, so at plot distance the colour is the whole answer;
 * a box whose colour disagreed with its word would be worse than no colour at all.</p>
 *
 * <p>Driven by {@link EditorDoorGhostsPacket}: the server pushes an absolute-position snapshot of
 * each door's <b>lower</b> cell, and which mouth it is, when the plot grid moves — a resize, a new room, a deletion — or when
 * the player toggles the ghosts, and an empty snapshot clears the cache. Positions are absolute
 * because a door stands one column outside its plot and so has no plot-local origin to be relative
 * to.</p>
 */
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class EditorDoorGhostRenderer {

    /** Outset on every axis so the outline sits just proud of the cell rather than z-fighting with it. */
    private static final double EXPAND = 0.004;

    /** How far a ghost is drawn from the camera, in chunks. Matches the strays' cull. */
    private static final int MAX_DISTANCE_CHUNKS = 4;

    /**
     * Beyond this (squared) distance from the camera a ghost is skipped, bounding the per-frame model
     * count. A door further out is still known client-side — it paints as soon as you fly into range
     * — so the cull costs visibility at distance, never correctness.
     */
    private static final double MAX_DISTANCE_SQ =
        (MAX_DISTANCE_CHUNKS * 16.0) * (MAX_DISTANCE_CHUNKS * 16.0);

    /** Entry mouth — blue, outline and label alike. */
    private static final float ENTRY_RED = 0.30f;
    private static final float ENTRY_GREEN = 0.65f;
    private static final float ENTRY_BLUE = 1.00f;

    /** Exit mouth — yellow. */
    private static final float EXIT_RED = 1.00f;
    private static final float EXIT_GREEN = 0.85f;
    private static final float EXIT_BLUE = 0.20f;

    /** Packed ARGB of the two above, for the label text. */
    private static final int ENTRY_TEXT_COLOR = 0xFF4DA6FF;
    private static final int EXIT_TEXT_COLOR = 0xFFFFD933;

    /**
     * Alpha ceiling for the door model, 0..255. High enough to read the wood grain, low enough that
     * the room behind it still shows through and the door cannot be mistaken for a placed block.
     */
    private static final int GHOST_ALPHA = 130;

    /** How many cells tall a door is — the lower cell from the packet, plus the upper above it. */
    private static final int DOOR_HEIGHT = 2;

    /** How far above the door's top the label floats, in blocks. Clear of the lintel, still on it. */
    private static final double LABEL_RISE = 0.45;

    /**
     * Glyph→world scale for the label. {@code EditorPlotLabelsRenderer.TEXT_SCALE}, so the two
     * editor overlays read at one size rather than at two.
     */
    private static final double TEXT_SCALE = EditorPlotLabelsRenderer.TEXT_SCALE;

    /** Most recent snapshot from the server: one tagged lower cell per door. Empty → no-op. */
    private static final List<EditorDoorGhostsPacket.Door> CACHE = new ArrayList<>();

    private EditorDoorGhostRenderer() {}

    /** Called from the packet handler on the client thread. */
    public static synchronized void applySnapshot(EditorDoorGhostsPacket packet) {
        CACHE.clear();
        if (packet.isEmpty()) return;
        CACHE.addAll(packet.doors());
    }

    /**
     * Wipe the cache on world quit so phantom ghosts don't survive across worlds — symmetric with
     * {@link EditorStrayGhostRenderer#onLoggingOut}.
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        applySnapshot(EditorDoorGhostsPacket.empty());
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        List<EditorDoorGhostsPacket.Door> snapshot;
        synchronized (EditorDoorGhostRenderer.class) {
            if (CACHE.isEmpty()) return;
            snapshot = new ArrayList<>(CACHE);
        }

        Minecraft mc = Minecraft.getInstance();
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);

        BlockRenderDispatcher blocks = mc.getBlockRenderer();
        MultiBufferSource ghost = type -> new GhostBuffer(buffer.getBuffer(RenderType.translucent()));
        for (EditorDoorGhostsPacket.Door d : snapshot) {
            if (culled(d, cam)) continue;
            for (int half = 0; half < DOOR_HEIGHT; half++) {
                door(ps, blocks, ghost, d.base().above(half), /*lower*/ half == 0);
            }
        }
        buffer.endBatch(RenderType.translucent());

        VertexConsumer lines = buffer.getBuffer(RenderType.lines());
        for (EditorDoorGhostsPacket.Door d : snapshot) {
            if (culled(d, cam)) continue;
            for (int half = 0; half < DOOR_HEIGHT; half++) {
                outline(ps, lines, d.base().above(half), d.entry());
            }
        }
        buffer.endBatch(RenderType.lines());

        // Labels last, in their own pass: the text batches through the font's own render types, and
        // interleaving them with the line batch above would break both batches into per-door draws.
        Font font = mc.font;
        for (EditorDoorGhostsPacket.Door d : snapshot) {
            if (culled(d, cam)) continue;
            label(ps, buffer, font, cam, d);
        }
        buffer.endBatch();

        ps.popPose();
    }

    /** True when {@code door} is further from the camera than {@link #MAX_DISTANCE_SQ}. */
    private static boolean culled(EditorDoorGhostsPacket.Door door, Vec3 cam) {
        return door.base().distToCenterSqr(cam.x, cam.y, cam.z) > MAX_DISTANCE_SQ;
    }

    /**
     * The floating <b>Entrance</b> / <b>Exit</b> word over one ghost.
     *
     * <p>A cylindrical billboard about world-up — the same basis
     * {@link EditorPlotLabelsRenderer#basis} builds for the plot panels, so a label and a panel
     * turn together as the author circles the plot rather than tilting independently.</p>
     *
     * <p>Drawn {@link Font.DisplayMode#SEE_THROUGH} at full brightness for the same reason the door
     * model is lit that way: an editor plot is unlit, the room's own wall stands between the author
     * and the far mouth, and a label that only shows when you have already walked to it answers the
     * question too late to be worth asking.</p>
     */
    private static void label(PoseStack ps, MultiBufferSource buffer, Font font, Vec3 cam,
                              EditorDoorGhostsPacket.Door door) {
        BlockPos base = door.base();
        Vec3 anchor = new Vec3(
            base.getX() + 0.5,
            base.getY() + DOOR_HEIGHT + LABEL_RISE,
            base.getZ() + 0.5);

        Vec3[] b = EditorPlotLabelsRenderer.basis(anchor, cam);
        Vec3 right = b[0], up = b[1], normal = b[2];

        ps.pushPose();
        ps.translate(anchor.x, anchor.y, anchor.z);
        ps.mulPose(new Quaternionf().setFromNormalized(new Matrix3f(
            (float) right.x, (float) right.y, (float) right.z,
            (float) up.x, (float) up.y, (float) up.z,
            (float) normal.x, (float) normal.y, (float) normal.z
        )));
        float scale = (float) TEXT_SCALE;
        ps.scale(scale, -scale, scale);

        String text = door.entry() ? "Entrance" : "Exit";
        Matrix4f mat = ps.last().pose();
        font.drawInBatch(text, -font.width(text) / 2f, -font.lineHeight / 2f,
            door.entry() ? ENTRY_TEXT_COLOR : EXIT_TEXT_COLOR, false, mat, buffer,
            Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT);
        ps.popPose();
    }

    /**
     * Render one half of a door at {@code pos}.
     *
     * <p>Lit at full brightness rather than from the world: the doorway stands in an unlit editor
     * plot at the edge of a bedrock cage, and a ghost that goes black in shadow is a ghost nobody
     * sees. The explicit {@link RenderType#translucent()} is what routes the model's own cutout
     * layer through {@link GhostBuffer} — {@code renderSingleBlock} honours the type it is handed.</p>
     */
    private static void door(PoseStack ps, BlockRenderDispatcher blocks, MultiBufferSource ghost,
                             BlockPos pos, boolean lower) {
        BlockState state = PortalCarriageBuilder.doorState(lower);
        ps.pushPose();
        ps.translate(pos.getX(), pos.getY(), pos.getZ());
        blocks.renderSingleBlock(state, ps, ghost,
            LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
            ModelData.EMPTY, RenderType.translucent());
        ps.popPose();
    }

    private static void outline(PoseStack ps, VertexConsumer vc, BlockPos pos, boolean entry) {
        LevelRenderer.renderLineBox(ps, vc,
            new AABB(
                pos.getX() - EXPAND, pos.getY() - EXPAND, pos.getZ() - EXPAND,
                pos.getX() + 1.0 + EXPAND, pos.getY() + 1.0 + EXPAND, pos.getZ() + 1.0 + EXPAND),
            entry ? ENTRY_RED : EXIT_RED,
            entry ? ENTRY_GREEN : EXIT_GREEN,
            entry ? ENTRY_BLUE : EXIT_BLUE,
            1.0f);
    }

    /**
     * A {@link VertexConsumer} that caps the alpha of everything written through it, so an opaque
     * block model comes out as a ghost.
     *
     * <p>{@code min} rather than an outright overwrite so a model that is already more transparent
     * than {@link #GHOST_ALPHA} stays that way. Every override returns {@code this} rather than the
     * delegate — the vertex builders chain these calls, and handing back the raw delegate would let
     * the rest of the chain write past the cap.</p>
     */
    private record GhostBuffer(VertexConsumer delegate) implements VertexConsumer {

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, Math.min(alpha, GHOST_ALPHA));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
            delegate.setNormal(normalX, normalY, normalZ);
            return this;
        }
    }
}
