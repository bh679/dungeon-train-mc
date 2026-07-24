package games.brennan.dungeontrain.client;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3d;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side carry for "static" train-contents entities (End Crystals, paintings, item frames)
 * that Sable never binds to their moving carriage (they don't run the per-tick hooks Sable's carry
 * relies on — see {@link games.brennan.dungeontrain.train.TrainStaticContentsCarrier}).
 *
 * <p>The server tells us each such entity's constant plot coordinate via
 * {@link games.brennan.dungeontrain.net.CarriedStaticEntityPacket}. Every client tick we position
 * the entity at {@code containingClientSubLevel.logicalPose().transformPosition(plotPos)} — the very
 * same pose Sable interpolates to draw the carriage blocks — and set its previous-tick render fields
 * so the renderer interpolates previous→current in phase with the blocks (Sable draws blocks
 * {@code lerp(partialTick, lastPose, logicalPose)}; the entity now lerps the transform of the same
 * two poses). The result is locked to the carriage with no cross-channel shimmer.</p>
 *
 * <p>We do not rely on server entity-position packets for these entities at all — the sub-level pose
 * is already synced for the blocks, so positioning from it is both smoother and cheaper.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ClientCarriedStatics {

    /** entityId -> constant plot (shipyard-local) coordinate delivered by the server. */
    private static final Map<Integer, Vector3d> PLOT_BY_ID = new ConcurrentHashMap<>();

    /** entityId -> previous tick's resolved world position, for render interpolation. */
    private static final Map<Integer, Vector3d> PREV_WORLD_BY_ID = new ConcurrentHashMap<>();

    private ClientCarriedStatics() {}

    /** Called from {@link games.brennan.dungeontrain.net.CarriedStaticEntityPacket#handle}. */
    public static void register(int entityId, double plotX, double plotY, double plotZ) {
        PLOT_BY_ID.put(entityId, new Vector3d(plotX, plotY, plotZ));
    }

    /** Drop all tracking on disconnect so ids don't leak across sessions. */
    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        PLOT_BY_ID.clear();
        PREV_WORLD_BY_ID.clear();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (PLOT_BY_ID.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        for (Map.Entry<Integer, Vector3d> e : PLOT_BY_ID.entrySet()) {
            int id = e.getKey();
            Entity entity = level.getEntity(id);
            if (entity == null) {
                // Out of range or gone. Drop both entries so the tracked set stays bounded to
                // crystals actually near the player; StartTracking re-sends the mapping (re-adding it
                // here) whenever the entity comes back into range. ConcurrentHashMap tolerates
                // removal mid-iteration.
                PREV_WORLD_BY_ID.remove(id);
                PLOT_BY_ID.remove(id);
                continue;
            }
            Vector3d plotPos = e.getValue();
            // Resolve the carriage sub-level that owns this fixed plot coordinate, on the client.
            SubLevel sub = Sable.HELPER.getContainingClient(plotPos);
            if (sub == null) continue;

            // transformPosition mutates the argument — hand it a throwaway copy.
            Vector3d world = sub.logicalPose().transformPosition(new Vector3d(plotPos));
            Vector3d prev = PREV_WORLD_BY_ID.get(id);
            if (prev == null) prev = new Vector3d(world);

            // Set previous-tick render position, then the current position, so the renderer
            // interpolates prev -> current in phase with the carriage blocks.
            entity.xOld = entity.xo = prev.x;
            entity.yOld = entity.yo = prev.y;
            entity.zOld = entity.zo = prev.z;
            entity.setPos(world.x, world.y, world.z);

            PREV_WORLD_BY_ID.put(id, world);
        }
    }
}
