package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Stage 2b P5 — client-side transform probe.
 *
 * <p>On each client tick (20 Hz), logs the render-transform of every
 * Valkyrien Skies ship within 200 blocks of the local player. Output goes
 * to the {@code games.brennan.dungeontrain.jitter} category alongside the
 * server-side probes so {@code grep [client]} + {@code grep [physics]} on
 * the same log file gives side-by-side server/client views of the ship.</p>
 *
 * <p>The key diagnostic question: does the client's rendered ship position
 * oscillate the same way the server's {@code effPos} does? If yes, the
 * user's hop is the server-side oscillation leaking through the network.
 * If no, the oscillation is server-internal noise and the real hop is from
 * a different source (entity carry, client-side prediction, voxel sync).</p>
 *
 * <p>Registered on {@code Dist.CLIENT} only so dedicated-server runs don't
 * pull in client-only classes ({@link Minecraft}, {@link ClientLevel}).</p>
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class JitterClientProbe {

    private static final Logger JITTER_LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.jitter");
    private static final double NEAR_RADIUS_SQ = 200.0 * 200.0;

    private static long clientTickCounter = 0L;

    private JitterClientProbe() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!JITTER_LOGGER.isTraceEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return;

        clientTickCounter++;

        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        for (ClientShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            ShipTransform transform = ship.getRenderTransform();
            Vector3dc shipPos = transform.getPosition();
            double dx = shipPos.x() - px;
            double dy = shipPos.y() - py;
            double dz = shipPos.z() - pz;
            if (dx * dx + dy * dy + dz * dz > NEAR_RADIUS_SQ) continue;

            Vector3dc pivot = transform.getPositionInModel();
            // Same voxel-A sample as server-side (model-space origin).
            Vector3d voxelA = new Vector3d(pivot).negate();
            transform.getRotation().transform(voxelA);
            voxelA.add(shipPos);

            AABBdc aabb = ship.getRenderAABB();

            JITTER_LOGGER.trace(
                "[client] clientTick={} shipId={} shipPos={} pivot={} voxelA_world={} aabb=[{},{},{} → {},{},{}]",
                clientTickCounter, ship.getId(),
                String.format("(%.6f, %.6f, %.6f)", shipPos.x(), shipPos.y(), shipPos.z()),
                String.format("(%.6f, %.6f, %.6f)", pivot.x(), pivot.y(), pivot.z()),
                String.format("(%.6f, %.6f, %.6f)", voxelA.x, voxelA.y, voxelA.z),
                String.format("%.3f", aabb.minX()), String.format("%.3f", aabb.minY()), String.format("%.3f", aabb.minZ()),
                String.format("%.3f", aabb.maxX()), String.format("%.3f", aabb.maxY()), String.format("%.3f", aabb.maxZ()));
        }
    }
}
