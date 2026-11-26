package games.brennan.dungeontrain.client.warlock;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.effects.warlock.SacrificialBoltPacket;
import games.brennan.dungeontrain.perks.effects.warlock.WarlockPerks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Draws Sacrificial Lightning ({@link SacrificialBoltPacket}): a vanilla-style jagged bolt from the sky,
 * as thick as {@link WarlockPerks#boltThickness} says, and {@link WarlockPerks#boltCount} of them,
 * the extras landing around the target. Thunder grows with {@link WarlockPerks#thunderVolume} and drops
 * in pitch as the bolt thickens; the sky flashes longer for more bolts. Client only: nothing in the world
 * is touched.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class SacrificialBoltClient {

    /** How long a bolt shows, in ticks; it flickers through new shapes as vanilla's does. */
    private static final int LIFE_TICKS = 8;
    private static final int SEGMENTS = 8;
    private static final float SEGMENT_HEIGHT = 16f;
    /** How far from the target the extra bolts land, in blocks. */
    private static final double SPREAD = 2.5;

    private record Bolt(Vec3 at, long seed, float thickness, int age) {
        Bolt older() {
            return new Bolt(at, seed, thickness, age + 1);
        }
    }

    private static final List<Bolt> LIVE = new ArrayList<>();

    private SacrificialBoltClient() {}

    public static void onBolt(SacrificialBoltPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float thickness = WarlockPerks.boltThickness(packet.damageHp());
        int count = WarlockPerks.boltCount(packet.damageHp());
        Random random = new Random(packet.seed());
        Vec3 target = new Vec3(packet.x(), packet.y(), packet.z());
        LIVE.add(new Bolt(target, random.nextLong(), thickness, 0));
        for (int i = 1; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double out = SPREAD * (0.5 + random.nextDouble() * 0.5);
            LIVE.add(new Bolt(target.add(Math.cos(angle) * out, 0, Math.sin(angle) * out), random.nextLong(),
                thickness * 0.8f, -random.nextInt(3)));
        }
        float volume = WarlockPerks.thunderVolume(packet.damageHp());
        float pitch = (float) Math.max(0.5, 1.0 / Math.sqrt(Math.max(0.25, thickness)));
        mc.level.playLocalSound(packet.x(), packet.y(), packet.z(), SoundEvents.LIGHTNING_BOLT_THUNDER,
            SoundSource.WEATHER, volume, pitch * (0.8f + random.nextFloat() * 0.2f), false);
        mc.level.playLocalSound(packet.x(), packet.y(), packet.z(), SoundEvents.LIGHTNING_BOLT_IMPACT,
            SoundSource.WEATHER, Math.max(0.5f, volume / 4f), pitch, false);
        mc.level.setSkyFlashTime(Math.min(10, 1 + count));
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (LIVE.isEmpty()) return;
        if (Minecraft.getInstance().level == null) {
            LIVE.clear();
            return;
        }
        List<Bolt> next = new ArrayList<>(LIVE.size());
        for (Bolt b : LIVE) if (b.age() + 1 < LIFE_TICKS) next.add(b.older());
        LIVE.clear();
        LIVE.addAll(next);
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || LIVE.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer out = buffer.getBuffer(RenderType.lightning());
        for (Bolt b : LIVE) {
            if (b.age() < 0) continue;
            ps.pushPose();
            ps.translate(b.at().x - cam.x, b.at().y - cam.y, b.at().z - cam.z);
            // a new shape every other tick, as vanilla's bolt flickers
            draw(out, ps.last().pose(), b.seed() + b.age() / 2, b.thickness());
            ps.popPose();
        }
        buffer.endBatch(RenderType.lightning());
    }

    /** Vanilla's bolt shape: a trunk of {@link #SEGMENTS} jagged segments and two branches, in four glowing layers. */
    private static void draw(VertexConsumer out, Matrix4f m, long seed, float thickness) {
        float[] xs = new float[SEGMENTS];
        float[] zs = new float[SEGMENTS];
        float x = 0f, z = 0f;
        Random shape = new Random(seed);
        for (int i = SEGMENTS - 1; i >= 0; i--) {
            xs[i] = x;
            zs[i] = z;
            x += shape.nextInt(11) - 5;
            z += shape.nextInt(11) - 5;
        }
        for (int layer = 0; layer < 4; layer++) {
            Random r = new Random(seed);
            for (int branch = 0; branch < 3; branch++) {
                int top = SEGMENTS - 1, bottom = 0;
                if (branch > 0) {
                    top = SEGMENTS - 1 - branch;
                    bottom = top - 2;
                }
                float bx = xs[top] - x, bz = zs[top] - z;
                for (int seg = top; seg >= bottom; seg--) {
                    float px = bx, pz = bz;
                    if (branch == 0) {
                        bx += r.nextInt(11) - 5;
                        bz += r.nextInt(11) - 5;
                    } else {
                        bx += r.nextInt(31) - 15;
                        bz += r.nextInt(31) - 15;
                    }
                    float wBottom = (0.1f + layer * 0.2f) * thickness;
                    float wTop = wBottom;
                    if (branch == 0) {
                        wBottom *= seg * 0.1f + 1f;
                        wTop *= (seg - 1) * 0.1f + 1f;
                    }
                    side(out, m, bx, bz, seg, px, pz, wBottom, wTop, false, false, true, false);
                    side(out, m, bx, bz, seg, px, pz, wBottom, wTop, true, false, true, true);
                    side(out, m, bx, bz, seg, px, pz, wBottom, wTop, true, true, false, true);
                    side(out, m, bx, bz, seg, px, pz, wBottom, wTop, false, true, false, false);
                }
            }
        }
    }

    private static void side(VertexConsumer out, Matrix4f m, float x1, float z1, int seg, float x2, float z2,
                             float w1, float w2, boolean a, boolean b, boolean c, boolean d) {
        float y1 = seg * SEGMENT_HEIGHT, y2 = (seg + 1) * SEGMENT_HEIGHT;
        out.addVertex(m, x1 + (a ? w2 : -w2), y1, z1 + (b ? w2 : -w2)).setColor(0.45f, 0.45f, 0.5f, 0.3f);
        out.addVertex(m, x2 + (a ? w1 : -w1), y2, z2 + (b ? w1 : -w1)).setColor(0.45f, 0.45f, 0.5f, 0.3f);
        out.addVertex(m, x2 + (c ? w1 : -w1), y2, z2 + (d ? w1 : -w1)).setColor(0.45f, 0.45f, 0.5f, 0.3f);
        out.addVertex(m, x1 + (c ? w2 : -w2), y1, z1 + (d ? w2 : -w2)).setColor(0.45f, 0.45f, 0.5f, 0.3f);
    }
}
