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
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
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

    /** entityId -> the previous/current world positions the last client tick resolved. */
    private static final Map<Integer, Carry> CARRY_BY_ID = new ConcurrentHashMap<>();

    /**
     * One entity's pair of render positions: {@code prev} is where it was at the end of the previous
     * client tick, {@code cur} where it is now. The renderer interpolates between them by partial
     * tick, in phase with the carriage blocks.
     */
    private record Carry(Vector3d prev, Vector3d cur) {}

    private ClientCarriedStatics() {}

    /** Called from {@link games.brennan.dungeontrain.net.CarriedStaticEntityPacket#handle}. */
    public static void register(int entityId, double plotX, double plotY, double plotZ) {
        PLOT_BY_ID.put(entityId, new Vector3d(plotX, plotY, plotZ));
    }

    /** Drop all tracking on disconnect so ids don't leak across sessions. */
    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        PLOT_BY_ID.clear();
        CARRY_BY_ID.clear();
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
                CARRY_BY_ID.remove(id);
                PLOT_BY_ID.remove(id);
                continue;
            }
            Vector3d plotPos = e.getValue();
            // Resolve the carriage sub-level that owns this fixed plot coordinate, on the client.
            SubLevel sub = Sable.HELPER.getContainingClient(plotPos);
            if (sub == null) continue;

            // transformPosition mutates the argument — hand it a throwaway copy.
            Vector3d world = sub.logicalPose().transformPosition(new Vector3d(plotPos));
            Carry last = CARRY_BY_ID.get(id);
            Carry carry = new Carry(last == null ? new Vector3d(world) : last.cur(), world);

            // Set previous-tick render position, then the current position, so the renderer
            // interpolates prev -> current in phase with the carriage blocks.
            apply(entity, carry);

            CARRY_BY_ID.put(id, carry);
        }
    }

    /**
     * Re-assert the tick's position once more per <i>frame</i>, before anything is drawn.
     *
     * <p>The server owns these entities' positions in world space and moves them every tick, which
     * makes vanilla broadcast ordinary move/teleport packets for them. Sable passes those straight
     * through — a carried static is at a plain world coordinate, so none of its sub-level packet
     * handling engages — and the client applies the position immediately, network-lagged by however
     * far the train travelled in the meantime. That lands between two of our tick corrections and
     * the decoration visibly flashes between the two places.</p>
     *
     * <p>Rather than fight the packet channel, we simply win the last word: {@code AFTER_SKY} runs
     * every frame before entities are rendered, so the drawn position is always the pose-locked one.
     * The values re-applied are exactly the ones the last client tick computed, leaving the renderer's
     * own previous→current interpolation intact.</p>
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;
        if (CARRY_BY_ID.isEmpty()) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        for (Map.Entry<Integer, Carry> e : CARRY_BY_ID.entrySet()) {
            Entity entity = level.getEntity(e.getKey());
            if (entity == null) continue;
            apply(entity, e.getValue());
        }
    }

    /** Pin {@code entity} to the carry's current position, with its previous-tick position behind it. */
    private static void apply(Entity entity, Carry carry) {
        entity.xOld = entity.xo = carry.prev().x;
        entity.yOld = entity.yo = carry.prev().y;
        entity.zOld = entity.zo = carry.prev().z;
        entity.setPos(carry.cur().x, carry.cur().y, carry.cur().z);
    }
}
