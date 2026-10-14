package games.brennan.dungeontrain.client;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3d;

/**
 * Keeps a dead rider on the carriage they died on, so the death moment plays over the train
 * instead of watching it roll away.
 *
 * <p>Sable carries a rider by feeding the carriage's motion into {@code LivingEntity.travel()} —
 * which a dead entity never runs. The carriage it was tracking is still recorded on the entity,
 * nothing clears that at death, and on the server Sable's own {@code ServerPlayer.tick} carry
 * keeps moving the corpse along; only the client stops, and for the local player the client is
 * what the camera follows. So while the local player is dead and still tracking a carriage, this
 * pins their ship-local position from the first dead tick and re-projects it through the
 * carriage's live pose every tick — the same frame the server is moving them in.</p>
 *
 * <p>Runs after the entity tick, so the render lerp from the tick's old position to this one is
 * smooth. Lets go the moment the player is alive again, the carriage is gone, or the world is.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DeadRiderCarry {

    private static SubLevel carriage;
    private static Vector3d local;

    private DeadRiderCarry() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !player.isDeadOrDying()) {
            release();
            return;
        }
        if (carriage == null) {
            pin(player);
            return;
        }
        if (carriage.isRemoved()) {
            release();
            return;
        }
        Vector3d world = carriage.logicalPose().transformPosition(new Vector3d(local));
        player.setPos(world.x, world.y, world.z);
    }

    /** First dead tick: remember which carriage was carrying them and where on it they were. */
    private static void pin(LocalPlayer player) {
        SubLevel sub = Sable.HELPER.getTrackingSubLevel(player);
        if (sub == null || sub.isRemoved()) return;
        carriage = sub;
        local = sub.logicalPose().transformPositionInverse(
                new Vector3d(player.getX(), player.getY(), player.getZ()));
    }

    private static void release() {
        carriage = null;
        local = null;
    }
}
