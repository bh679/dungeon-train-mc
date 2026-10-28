package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;

/**
 * One body, not two: while the death scene has a live ragdoll body ({@link DeathCameraCycle#bodyRagdollLive}),
 * the dead local player's own model is not drawn. The ragdoll is the body; the player entity only
 * rides along under it so the camera and the server keep their anchor ({@link DeadRiderCarry}).
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DeathBodyRender {

    private DeathBodyRender() {}

    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getEntity() != mc.player || mc.player == null) return;
        if (mc.player.isDeadOrDying() && DeathCameraCycle.bodyRagdollLive()) event.setCanceled(true);
    }
}
