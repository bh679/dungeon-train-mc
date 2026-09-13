package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.PortalRoomSky;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.material.FogType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Colours the fog inside a portal room to match the sky it stands under — Nether red, End dark —
 * the fog half of what {@code LightTexturePortalRoomMixin} does to the lightmap.
 *
 * <p>A room set to a Nether or End sky already has its lightmap tinted and, under a shader pack,
 * the pack's world swapped ({@code ShaderWorld}); without shaders the fog stayed the Overworld's
 * pale blue, which is the one thing in view that said "this is not the Nether". This takes the
 * same colours the transition bands use — {@link NetherFogEvents}' wastes red and
 * {@link VoidSkyEvents}' 0.15x darkening — so a room under a Nether sky fogs like the Nether band
 * and a room under an End sky like the End band.</p>
 *
 * <p><b>Weighted by the room's own ease.</b> {@link ClientPortalRoomSky#applied()} is the same
 * 0..1 the lightmap lift rides, so fog and light come up together over the walk in and fade
 * together on the way out; the corridor ramp is already folded into it. Wherever the sky packet
 * applies this applies — a live dimensional carriage, a {@code /dt portal test} session, and the
 * editor plot the room is authored on — which is the one-implementation rule
 * {@code EditorPlotSky} spells out.</p>
 *
 * <p>Daylight and Day/Night skies leave the fog alone: they stand under an ordinary sky. Underwater
 * and lava fog are left alone for the reason {@link PortalRoomFogEvents} gives — those are the game
 * telling the player what they are standing in.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PortalRoomSkyFogEvents {

    /** How far the fog is darkened at full strength under an End sky — the End band's own figure. */
    private static final float END_DARKEN = 0.85f;

    private PortalRoomSkyFogEvents() {}

    @SubscribeEvent
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        if (event.getCamera().getFluidInCamera() != FogType.NONE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        float w = ClientPortalRoomSky.applied();
        if (w <= 0.0f) return;
        PortalRoomSky sky = ClientPortalRoomSky.sky();

        int before = ShaderDiagnostics.recording() ? ShaderDiagnostics.packFog(event) : 0;
        switch (sky) {
            case NETHER -> {
                event.setRed(lerp(event.getRed(), NetherFogEvents.NETHER_FOG_R, w));
                event.setGreen(lerp(event.getGreen(), NetherFogEvents.NETHER_FOG_G, w));
                event.setBlue(lerp(event.getBlue(), NetherFogEvents.NETHER_FOG_B, w));
            }
            case END -> {
                float f = 1.0f - END_DARKEN * w;
                event.setRed(event.getRed() * f);
                event.setGreen(event.getGreen() * f);
                event.setBlue(event.getBlue() * f);
            }
            default -> {
                return; // an ordinary sky — nothing to colour
            }
        }
        if (ShaderDiagnostics.recording()) {
            ShaderDiagnostics.recordFogColor("room", before, ShaderDiagnostics.packFog(event));
        }
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }
}
