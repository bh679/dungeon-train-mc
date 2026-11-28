package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.effects.hoarder.ChestSense;
import games.brennan.dungeontrain.perks.effects.hoarder.ChestSenseClientState;
import games.brennan.dungeontrain.perks.effects.hoarder.ChestSenseRarity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chest Sense's outlines and ring. Every container in the last pulse ({@link ChestSenseClientState})
 * glows with the spectral-arrow outline (vanilla's glow post pass, with a thicker edge), in the colour of
 * its rarest item ({@link ChestSenseRarity}); a vault's colour follows the item it is showing.
 *
 * <p>The container is drawn again as a silhouette only, never into the world: its block entity renderer
 * (chests, shulker boxes, pots, the vault's spinning item) and its block model (barrels, the vault's cage)
 * both go through {@link ChestSenseGlow#source}, which keeps a render type's outline twin and drops the
 * rest. {@link ChestSenseGlow} runs the edge pass on a target of its own, wider than vanilla's, so the
 * line is bold; the outline type has no depth test, so the glow shows through walls. Drawn at
 * {@code AFTER_LEVEL}, under the hand, as vanilla's glow is.</p>
 *
 * <p>Containers on a carriage are plot-local and placed through their sub-level's
 * {@link ClientSubLevel#renderPose(float)}, the pose Sable draws the carriage with, so the glow rides a
 * moving train exactly.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ChestSenseRenderer {

    /** Particles in the ring, spread evenly around the player. */
    private static final int RING_POINTS = 40;

    private ChestSenseRenderer() {}

    /**
     * The pulse's ring: each client tick for the first second after a pulse, a circle of particles at
     * the player's feet whose radius grows from 0 to the range ({@link ChestSenseClientState#ringRadius}).
     */
    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        float radius = ChestSenseClientState.ringRadius();
        if (radius <= 0F) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.isPaused()) return;
        Vec3 at = mc.player.position();
        for (int i = 0; i < RING_POINTS; i++) {
            double angle = Math.PI * 2.0 * i / RING_POINTS;
            mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.WAX_ON,
                at.x + Math.cos(angle) * radius, at.y + 0.2, at.z + Math.sin(angle) * radius, 0.0, 0.0, 0.0);
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        List<ChestSense.Sensed> sensed = ChestSenseClientState.active();
        if (sensed.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !ChestSenseGlow.begin(mc)) return;

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        // AFTER_LEVEL runs with the camera's rotation popped off; put it back for the silhouettes.
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.mul(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        try {
            drawAll(mc, level, sensed, event.getCamera().getPosition(), partialTick);
        } finally {
            ChestSenseGlow.endDraw(mc);
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
        ChestSenseGlow.present(mc, partialTick);
    }

    private static void drawAll(Minecraft mc, ClientLevel level, List<ChestSense.Sensed> sensed, Vec3 cam, float partialTick) {
        Map<UUID, ClientSubLevel> subs = new HashMap<>();
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null) {
            for (ClientSubLevel sl : container.getAllSubLevels()) subs.put(sl.getUniqueId(), sl);
        }
        PoseStack ps = new PoseStack();
        for (ChestSense.Sensed s : sensed) {
            Pose3dc pose = null;
            if (s.subLevel() != null) {
                ClientSubLevel sub = subs.get(s.subLevel());
                if (sub == null) continue;
                pose = sub.renderPose(partialTick);
                if (pose == null) continue;
            }
            BlockState state = level.getBlockState(s.pos());
            if (state.isAir()) continue;
            BlockEntity be = level.getBlockEntity(s.pos());
            MultiBufferSource buffers = ChestSenseGlow.source(tierNow(s, be).rgb());
            ps.pushPose();
            ps.last().pose().mul(placement(s.pos(), pose, cam));
            drawContainer(mc, ps, buffers, state, be, partialTick);
            ps.popPose();
        }
    }

    /** A vault's colour is the band of the item it shows now; anything else keeps its pulse colour. */
    private static ChestSenseRarity.Tier tierNow(ChestSense.Sensed s, BlockEntity be) {
        if (!s.isVault()) return s.tier();
        if (!(be instanceof VaultBlockEntity vault)) return ChestSenseRarity.Tier.UNKNOWN;
        ItemStack shown = vault.getSharedData().getDisplayItem();
        if (shown.isEmpty()) return ChestSenseRarity.Tier.UNKNOWN;
        return s.vaultTiers().getOrDefault(BuiltInRegistries.ITEM.getKey(shown.getItem()), ChestSenseRarity.Tier.UNKNOWN);
    }

    /** Draws the container's model and block entity into the outline buffer; true when anything was drawn. */
    private static boolean drawContainer(Minecraft mc, PoseStack ps, MultiBufferSource buffers,
                                         BlockState state, BlockEntity be, float partialTick) {
        boolean drew = false;
        if (state.getRenderShape() == RenderShape.MODEL) {
            VertexConsumer vc = buffers.getBuffer(RenderType.outline(TextureAtlas.LOCATION_BLOCKS));
            mc.getBlockRenderer().getModelRenderer().renderModel(ps.last(), vc, state,
                mc.getBlockRenderer().getBlockModel(state), 1.0F, 1.0F, 1.0F,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            drew = true;
        }
        if (be != null) {
            BlockEntityRenderer<BlockEntity> renderer = mc.getBlockEntityRenderDispatcher().getRenderer(be);
            if (renderer != null) {
                renderer.render(be, partialTick, ps, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                drew = true;
            }
        }
        return drew;
    }

    /**
     * Camera-relative matrix taking block-local space (0..1 across the block) to where the block is drawn.
     * On a carriage the corner and its three unit edges are put through the render pose, so the matrix
     * carries the carriage's rotation without depending on how the pose stores it.
     */
    private static Matrix4f placement(BlockPos pos, Pose3dc pose, Vec3 cam) {
        Vector3d o = new Vector3d(pos.getX(), pos.getY(), pos.getZ());
        Vector3d x = new Vector3d(pos.getX() + 1, pos.getY(), pos.getZ());
        Vector3d y = new Vector3d(pos.getX(), pos.getY() + 1, pos.getZ());
        Vector3d z = new Vector3d(pos.getX(), pos.getY(), pos.getZ() + 1);
        if (pose != null) {
            pose.transformPosition(o, o);
            pose.transformPosition(x, x);
            pose.transformPosition(y, y);
            pose.transformPosition(z, z);
        }
        x.sub(o);
        y.sub(o);
        z.sub(o);
        return new Matrix4f(
            (float) x.x, (float) x.y, (float) x.z, 0F,
            (float) y.x, (float) y.y, (float) y.z, 0F,
            (float) z.x, (float) z.y, (float) z.z, 0F,
            (float) (o.x - cam.x), (float) (o.y - cam.y), (float) (o.z - cam.z), 1F);
    }
}
