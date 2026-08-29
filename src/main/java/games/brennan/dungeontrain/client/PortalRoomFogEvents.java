package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.sound.ClientPortalTrainAudio;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Draws the fog that makes an endless portal room read as endless — the counterpart to
 * {@link NetherFogEvents} for the two tiling {@code PortalRoomMode}s.
 *
 * <p><b>Shrunk, never expanded.</b> The values the event arrives with are vanilla's, already computed
 * from the player's own render distance and fog setting, so taking the minimum is what keeps this
 * from ever letting somebody see <i>further</i> than they had chosen to. A room whose fog distance is
 * beyond what they can see anyway changes nothing, which is why a small room does not look fogged
 * until enough copies of it are standing to be worth hiding.</p>
 *
 * <p><b>The corridor ramps into it.</b> A portal corridor is outside the room's bounds, so the box
 * test alone left the fog at vanilla for the whole walk and closed it in at the room-side door — all
 * of the transition in its last step. {@code ClientPortalRoomFog} rides {@code ClientPortalCrossing}
 * through the corridor instead, which is the same ramp the corridor's own lightmap lift uses.</p>
 *
 * <p><b>Vanilla's far plane is also an input.</b> It is the far end of the ease the cache runs, so
 * the frame a player arrives in a room looks exactly like the frame before it and the fog closes in
 * from there. Handing only the camera position over and easing up from zero is what made a portal
 * swap flash — a far plane of four blocks is a screenful of fog colour, not a hint of fog.</p>
 *
 * <p><b>The event has to be cancelled for any of it to apply.</b> {@code ClientHooks.onFogRender}
 * only pushes the near and far planes into the shader when the event was cancelled — an uncancelled
 * handler that sets them is silently ignored. So this cancels, but only on the frames where it
 * actually reduced something, leaving vanilla in charge the rest of the time.</p>
 *
 * <p>Underwater and in lava are left alone. Those fog types are the game telling the player what they
 * are standing in, which matters more than the room's atmosphere, and cancelling would also discard
 * whatever a fluid type's own {@code modifyFogRender} had just set.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PortalRoomFogEvents {

    private PortalRoomFogEvents() {}

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (event.getType() != FogType.NONE) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Vec3 camera = event.getCamera().getPosition();
        // Vanilla's own far plane goes in as well as coming out: it is the "no room" end of the ease,
        // so engaging can never hand back a distance shorter than the room asked for. See
        // ClientPortalRoomFog#fogDistanceAt — easing up from zero instead is what used to fill the
        // screen with fog colour for the first frames of a portal swap.
        // The corridor ramp goes in as well, so a player walking a portal corridor arrives with the
        // room already at its own fog distance instead of watching it close in at the doorway. Read
        // rather than advanced: LightTexturePortalCrossingMixin owns that ease.
        float distance = ClientPortalRoomFog.fogDistanceAt(
            camera.x, camera.y, camera.z, event.getFarPlaneDistance(),
            ClientPortalCrossing.current());
        if (distance <= 0.0f) return;

        float far = Math.min(event.getFarPlaneDistance(), distance);
        // The near plane rides in proportionally, so a heavily shrunk fog thickens rather than
        // becoming a hard edge at arm's length.
        float near = Math.min(event.getNearPlaneDistance(), far * 0.25f);
        if (far >= event.getFarPlaneDistance() && near >= event.getNearPlaneDistance()) return;

        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance(near);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // Belt to the region cache's braces. Leaving a world drops the room anyway — the bounds
        // would not contain the player wherever they arrived next — but a stale region is exactly
        // the thing that used to strand somebody permanently fogged, so it is cleared outright.
        ClientPortalRoomFog.reset();
        // The daylit-room box is the same shape of cache with the same failure mode — a stale one
        // would leave the next world's lightmap lifted — so it goes on the same pass.
        ClientPortalRoomSky.reset();
        // The corridor hold is not a region but has the same failure mode from the other direction:
        // it is a live value with a countdown, and a client that logs out mid-corridor would carry a
        // partly-eased lift into the next world's first second. Cleared outright for the same reason.
        ClientPortalCrossing.reset();
        // The engine-audio region is the same shape of cache with the same failure mode — a stale one
        // would have the train audible in the next world's portal-shaped nowhere — so it goes too.
        ClientPortalTrainAudio.reset();
    }
}
