package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.vertex.PoseStack;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.CometPacket;
import games.brennan.dungeontrain.worldgen.SpheresComets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;

/**
 * Spheres-band comets as the client sees them: flown from the launch packet alone (start + velocity × ticks since)
 * and drawn here — a full-bright magma block spinning on its own tilted axis ({@link SpheresComets#spinAxis}) and a
 * tail of flames and glowing sparks laid along each tick's step — with a roar that builds as it nears
 * ({@link CometSound}).
 * The server only says when one starts and when it is gone ({@link CometPacket}); the explosion that ends a hit is
 * an ordinary server explosion. Render-thread state only.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ClientComets {

    /** Matches the server's longest flight. A miss that fell past the train gets no end packet: it flies on down
     *  into the abyss until this runs out. */
    private static final int MAX_AGE_TICKS = 240;
    private static final float SCALE = 1.6F;
    /** Points per tick the tail is laid at along the comet's step. */
    private static final int TAIL_STEPS = 2;
    private static final BlockState MAGMA = Blocks.MAGMA_BLOCK.defaultBlockState();

    private record Comet(int id, Vec3 start, Vec3 velocity, int age, CometSound sound) {
        Vec3 at(double ticks) {
            return start.add(velocity.scale(ticks));
        }

        Comet older() {
            return new Comet(id, start, velocity, age + 1, sound);
        }
    }

    private static List<Comet> live = List.of();

    private ClientComets() {}

    public static void accept(CometPacket packet) {
        List<Comet> next = new ArrayList<>(live.size() + 1);
        for (Comet c : live) {
            if (c.id() != packet.id()) next.add(c);
            else c.sound().stop();
        }
        if (packet.landed()) CometShake.impact(new Vec3(packet.x(), packet.y(), packet.z()));
        if (!packet.ended()) {
            Vec3 start = new Vec3(packet.x(), packet.y(), packet.z());
            next.add(new Comet(packet.id(), start, new Vec3(packet.vx(), packet.vy(), packet.vz()), 0,
                CometSound.start(start)));
        }
        live = List.copyOf(next);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (live.isEmpty()) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || Minecraft.getInstance().isPaused()) return;
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        List<Comet> next = new ArrayList<>(live.size());
        for (Comet c : live) {
            Comet older = c.older();
            if (older.age() >= MAX_AGE_TICKS) {
                c.sound().stop();
                continue;
            }
            Vec3 at = older.at(older.age());
            trail(level, c.at(c.age()), at, c.velocity());
            older.sound().update(at, at.distanceTo(ear));
            next.add(older);
        }
        live = List.copyOf(next);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        for (Comet c : live) c.sound().stop();
        live = List.of();
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || live.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        for (Comet c : live) {
            double ticks = c.age() + partial;
            Vec3 at = c.at(ticks);
            ps.pushPose();
            ps.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
            ps.mulPose(new Quaternionf().fromAxisAngleRad(SpheresComets.spinAxis(c.id()),
                SpheresComets.spinAngle(c.id(), ticks)));
            ps.scale(SCALE, SCALE, SCALE);
            ps.translate(-0.5, -0.5, -0.5);
            mc.getBlockRenderer().renderSingleBlock(MAGMA, ps, buffer, LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.solid());
            ps.popPose();
        }
        buffer.endBatch(RenderType.solid());
    }

    /** Flames blown back off the head and end-rod sparks that hang behind it, so the tail traces the path. */
    private static void trail(ClientLevel level, Vec3 from, Vec3 to, Vec3 velocity) {
        RandomSource random = level.random;
        Vec3 back = velocity.normalize().scale(-1.0);
        for (int i = 0; i < TAIL_STEPS; i++) {
            Vec3 at = from.lerp(to, (i + 1.0) / TAIL_STEPS);
            for (int f = 0; f < 2; f++) {
                Vec3 dir = back.add(jitter(random, 0.25)).scale(0.25);
                level.addParticle(ParticleTypes.FLAME, true, at.x, at.y, at.z, dir.x, dir.y, dir.z);
            }
            Vec3 drift = back.add(jitter(random, 0.6)).scale(0.04);
            level.addParticle(ParticleTypes.END_ROD, true, at.x, at.y, at.z, drift.x, drift.y, drift.z);
        }
        if (random.nextBoolean()) {
            level.addParticle(ParticleTypes.LAVA, true, to.x + random.nextGaussian() * 0.3,
                to.y + random.nextGaussian() * 0.3, to.z + random.nextGaussian() * 0.3, 0.0, 0.0, 0.0);
        }
    }

    private static Vec3 jitter(RandomSource random, double spread) {
        return new Vec3((random.nextDouble() - 0.5) * spread, (random.nextDouble() - 0.5) * spread,
            (random.nextDouble() - 0.5) * spread);
    }
}
