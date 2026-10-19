package games.brennan.dungeontrain.client.skybox;

import com.mojang.blaze3d.vertex.BufferBuilder;
import games.brennan.dungeontrain.client.ClientUpsideDownBand;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.worldgen.legacy.preset.AmplifiedDrop;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * A sheet of sky over the sunk zone's attic lid, so the dimensional carriages stamped up there are
 * not seen hanging overhead from the train.
 *
 * <p><b>Why.</b> In the sunk zone (the gap into Amplified and Amplified's slot) the twins move to an
 * attic over a barrier lid at {@link AmplifiedDrop#lidY}. Barrier is invisible, and the seal cut has
 * no roof there ({@code PortalSealPlane.cutForAmplified}) — a section cut at the lid would keep the
 * twin's bottom rows, which share the lid's section, and shear real peaks just outside the zone. The
 * upside-down band has no such problem because its lid is bedrock.</p>
 *
 * <p><b>How.</b> One horizontal quad per run of sunk columns, under the lid row (below the rooms' lock skin), fed to
 * {@link SkyboxPunchRenderer} as a {@code LIVE} skybox face: the punch writes its depth before any
 * terrain, so everything beyond it — the attic and its twins — fails the depth test and the sky
 * already painted there shows through. Clouds sit below the lid and still draw. Drawn only for a
 * camera under the lid; from the attic the trimmed seal cut already hides the world below.</p>
 */
public final class SunkLidSkyPlane {

    /** Sampling grid along X: chunk-wide, read at each chunk's centre like {@code SunkZone.chunkColumn}. */
    private static final int CHUNK = 16;

    private SunkLidSkyPlane() {}

    /** A run of X columns {@code [minX, maxX)} the plane covers. */
    public record Span(int minX, int maxX) {}

    /**
     * The runs of sunk chunks across {@code [camX - reach, camX + reach]}, merged where they touch.
     * Pure, so it unit-tests without a client.
     */
    public static List<Span> spans(int camX, int reach, IntPredicate inZone) {
        List<Span> out = new ArrayList<>();
        int from = Math.floorDiv(camX - reach, CHUNK) * CHUNK;
        int to = camX + reach;
        int runStart = Integer.MIN_VALUE;
        for (int x = from; x <= to; x += CHUNK) {
            boolean sunk = inZone.test(x + CHUNK / 2);
            if (sunk && runStart == Integer.MIN_VALUE) {
                runStart = x;
            } else if (!sunk && runStart != Integer.MIN_VALUE) {
                out.add(new Span(runStart, x));
                runStart = Integer.MIN_VALUE;
            }
        }
        if (runStart != Integer.MIN_VALUE) {
            out.add(new Span(runStart, Math.floorDiv(to, CHUNK) * CHUNK + CHUNK));
        }
        return out;
    }

    /**
     * The plane's world Y this frame, or {@link Integer#MIN_VALUE} when it should not draw.
     * Cheap: a few field reads, and a 64-block zone sample only once the lid is known to be active.
     */
    public static int planeY(Vec3 cam, int reach) {
        if (!ClientDisplayConfig.isPortalTwinSealCulling()) return Integer.MIN_VALUE;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().equals(Level.OVERWORLD)) return Integer.MIN_VALUE;
        AmplifiedDrop drop = ClientUpsideDownBand.amplifiedDrop();
        if (drop == null || !drop.active()) return Integer.MIN_VALUE;
        // The lid row's underside: the attic rooms' lock skin fills the row above it, so a plane on
        // the lid's top face shared the skin's depth and z-fought it. Terrain is capped at the lid.
        int planeY = drop.lidY();
        if (cam.y >= planeY) return Integer.MIN_VALUE;
        if (!ClientUpsideDownBand.isSunkZoneWithin(Mth.floor(cam.x), reach)) return Integer.MIN_VALUE;
        return planeY;
    }

    /** How far along X and Z the plane reaches: the render distance plus a chunk of slack. */
    public static int reach() {
        return (Minecraft.getInstance().options.getEffectiveRenderDistance() + 1) * CHUNK;
    }

    /** Emit this frame's plane quads; returns how many were written. */
    public static int emit(BufferBuilder builder, Matrix4f frustumMatrix, Frustum frustum, Vec3 cam) {
        int reach = reach();
        int planeY = planeY(cam, reach);
        if (planeY == Integer.MIN_VALUE) return 0;
        double minZ = cam.z - reach;
        double maxZ = cam.z + reach;
        int quads = 0;
        for (Span span : spans(Mth.floor(cam.x), reach, ClientUpsideDownBand::isInSunkZone)) {
            if (!frustum.isVisible(new AABB(span.minX(), planeY - 0.01, minZ, span.maxX(), planeY + 0.01, maxZ))) {
                continue;
            }
            float y = (float) (planeY - cam.y);
            float x0 = (float) (span.minX() - cam.x);
            float x1 = (float) (span.maxX() - cam.x);
            float z0 = (float) (minZ - cam.z);
            float z1 = (float) (maxZ - cam.z);
            builder.addVertex(frustumMatrix, x0, y, z0);
            builder.addVertex(frustumMatrix, x0, y, z1);
            builder.addVertex(frustumMatrix, x1, y, z1);
            builder.addVertex(frustumMatrix, x1, y, z0);
            quads++;
        }
        return quads;
    }
}
