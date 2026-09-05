package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.client.ClientPortalCrossing;
import games.brennan.dungeontrain.client.ClientPortalRoomSky;
import games.brennan.dungeontrain.client.ShaderDiagnostics;
import games.brennan.dungeontrain.portal.PortalRoomSky;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lifts the world lightmap toward a chosen sky while the camera is inside a portal room whose
 * template asked to be lit as though it stood outdoors — see {@link PortalRoomSky}.
 *
 * <p>The fourth sibling of {@link LightTextureUpsideDownBandMixin},
 * {@link LightTextureEndBandMixin} and {@link LightTextureNetherBandMixin}, and mechanically
 * identical to all three: the same two hooks on {@code updateLightTexture}, the same crossfade by an
 * intensity that a client-side region cache evaluates at the camera
 * ({@link ClientPortalRoomSky#advance}), so the lighting eases in and out rather than popping at the
 * room's wall. What differs is only that the intensity comes from a <i>box</i> the server named
 * rather than from a band measured off world-X, and that the tint and the pin are chosen per room
 * instead of being fixed.</p>
 *
 * <p>Portal rooms are stamped in the basement band far below the world's bedrock row, where there is
 * no daylight and — because vanilla stores one sky-source Y per column — no way to give them any
 * without taking it from the real surface overhead. So this does not pretend to be skylight: it
 * lifts what the room's own lamps are already doing toward the colour and level of a sky. A room
 * built to be daylit reads as daylit; an unlit cellar tagged {@code day} reads as a brighter unlit
 * cellar.</p>
 *
 * <p>Purely client-side and per-player: each client evaluates its own camera against the room box it
 * was last told about, so a player outside sees ordinary lighting at the same instant. No server
 * state, no saved-world change, and no game logic — mob spawns, the clock, the light the engine
 * actually stores — is affected. Self-disables ({@code t == 0}) outside the overworld, outside every
 * room, and whenever the server has the feature turned off, since it then names no rooms at all.</p>
 *
 * <p>Shares its target method with the three band mixins. A portal room inside an upside-down band
 * would have both handlers apply and their lifts compose, which is harmless — both are lifts toward
 * daylight — and vanishingly rare, since one is measured underground and the other at the surface.</p>
 */
@Mixin(LightTexture.class)
public abstract class LightTexturePortalRoomMixin {

    /** Neutral daylight tint, for the two kinds that stand under an ordinary sky. */
    @Unique
    private static final Vector3f DUNGEONTRAIN_ROOM_DAY_TINT = new Vector3f(1.0F, 1.0F, 1.0F);

    /** The End band's tint, so a room under an End sky matches the band that sky came from. */
    @Unique
    private static final Vector3f DUNGEONTRAIN_ROOM_END_TINT = new Vector3f(0.99F, 1.12F, 1.0F);

    /** The Nether band's dim warm ember, for the same reason. */
    @Unique
    private static final Vector3f DUNGEONTRAIN_ROOM_NETHER_TINT = new Vector3f(0.65F, 0.32F, 0.20F);

    /** Max floor-lift toward a bright tint at full intensity, matching the End and upside-down bands. */
    @Unique
    private static final float DUNGEONTRAIN_ROOM_LIFT = 0.25F;

    /**
     * The Daylight kind's lift — far heavier, because for that kind the lift is the whole of the
     * effect rather than a floor under it.
     *
     * <p>{@link #dungeontrain$pinRoomSky} pins the sky-darken factor to noon, which is what makes a
     * daylit room daylit — <b>where there is sky light to scale</b>. A portal room is stamped in the
     * sealed basement under the world's bedrock, where there is none at all: the pin multiplies zero
     * and the room was left with the {@value #DUNGEONTRAIN_ROOM_LIFT} floor alone, which reads as a
     * grey wash with the author's own torches standing out of it. The editor plot looked right only
     * because it stands in the open sky at the build height, where real sky light reaches it — so a
     * room built under a sky it will never have was tested under one it does not have either.</p>
     *
     * <p>At this strength a sealed room reads as an overcast noon: bright, and flat enough that
     * block light stops being the thing that shapes it — which is what standing outdoors at midday
     * actually looks like. {@link PortalRoomSky#CYCLE} is deliberately left on the ordinary floor:
     * its whole point is darkening at dusk, and a constant lift this heavy would be a room that
     * never does.</p>
     */
    @Unique
    private static final float DUNGEONTRAIN_ROOM_DAY_LIFT = 0.70F;

    /** The Nether's heavier lift, so its ember floor reads as a floor rather than a tint. */
    @Unique
    private static final float DUNGEONTRAIN_ROOM_NETHER_LIFT = 0.30F;

    /**
     * Sky-darken the Nether kind pins toward — well below noon, so a Nether-skied room stays a
     * perpetual dim glow rather than brightening to full daylight. Same constant the Nether band
     * uses.
     */
    @Unique
    private static final float DUNGEONTRAIN_ROOM_NETHER_SKY = 0.60F;

    /**
     * Room intensity for the in-progress lightmap rebuild, captured by the pin hook (which runs once,
     * before the 16×16 cell loop) and reused by the per-cell floor lift, so the region is evaluated
     * once per rebuild rather than 256 times.
     */
    @Unique
    private float dungeontrain$roomT;

    /** Which sky that intensity is toward, captured on the same pass. */
    @Unique
    private PortalRoomSky dungeontrain$roomSky = PortalRoomSky.NONE;

    /**
     * Daytime pin: lerp the sky-darken factor toward the room's sky by the room intensity, and cache
     * both for {@link #dungeontrain$liftRoomFloor}.
     *
     * <p>{@link PortalRoomSky#CYCLE} deliberately does not pin — a room that is meant to darken at
     * dusk has to keep vanilla's own factor — but it still advances the ease, so its floor lift
     * crossfades like every other kind's.</p>
     */
    @ModifyExpressionValue(
            method = "updateLightTexture(F)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getSkyDarken(F)F"
            )
    )
    private float dungeontrain$pinRoomSky(float skyDarken) {
        float t = dungeontrain$roomIntensity();
        this.dungeontrain$roomT = t;
        this.dungeontrain$roomSky = t > 0.0F ? ClientPortalRoomSky.sky() : PortalRoomSky.NONE;
        if (ShaderDiagnostics.recording()) {
            // The lift the per-cell hook below will apply at this intensity — the number that
            // decides whether a pack discarding the lightmap is visible at all.
            float lift = (this.dungeontrain$roomSky == PortalRoomSky.NETHER
                ? DUNGEONTRAIN_ROOM_NETHER_LIFT
                : DUNGEONTRAIN_ROOM_LIFT) * t;
            ShaderDiagnostics.recordRoomSky(this.dungeontrain$roomSky.name(), t, lift);
        }
        if (t <= 0.0F || !this.dungeontrain$roomSky.pinsDaylight()) return skyDarken;
        float target = this.dungeontrain$roomSky == PortalRoomSky.NETHER
            ? DUNGEONTRAIN_ROOM_NETHER_SKY
            : 1.0F;
        return Mth.lerp(t, skyDarken, target);
    }

    /**
     * Floor: after vanilla has combined block and sky light for this cell, lift it toward the room's
     * tint so shaded faces sit at a sky-lit floor rather than falling to black.
     */
    @Inject(
            method = "updateLightTexture(F)V",
            at = @At(
                    value = "INVOKE",
                    shift = At.Shift.AFTER,
                    target = "Lnet/minecraft/client/renderer/DimensionSpecialEffects;"
                            + "adjustLightmapColors(Lnet/minecraft/client/multiplayer/ClientLevel;FFFFIILorg/joml/Vector3f;)V"
            )
    )
    private void dungeontrain$liftRoomFloor(float partialTicks, CallbackInfo ci,
                                            @Local(ordinal = 1) Vector3f cellColor) {
        float t = this.dungeontrain$roomT;
        if (t <= 0.0F) return;
        PortalRoomSky sky = this.dungeontrain$roomSky;
        Vector3f tint = switch (sky) {
            case NETHER -> DUNGEONTRAIN_ROOM_NETHER_TINT;
            case END -> DUNGEONTRAIN_ROOM_END_TINT;
            default -> DUNGEONTRAIN_ROOM_DAY_TINT;
        };
        float lift = switch (sky) {
            case NETHER -> DUNGEONTRAIN_ROOM_NETHER_LIFT;
            case DAY -> DUNGEONTRAIN_ROOM_DAY_LIFT;
            default -> DUNGEONTRAIN_ROOM_LIFT;
        };
        cellColor.lerp(tint, lift * t);
    }

    /**
     * Room intensity at the camera, {@code 0} outside the overworld or any daylit room.
     *
     * <p>Advances the ease as a side effect, which is why it is called exactly once per rebuild. The
     * overworld guard is belt to the region cache's braces: portal rooms only ever exist there, and a
     * cached box would not contain a player who had left, but a Nether coordinate can land inside an
     * overworld box and the check is one comparison.</p>
     */
    @Unique
    private static float dungeontrain$roomIntensity() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return 0.0F;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        // The corridor ramp goes in with the camera, so a daylit room's light comes up over the walk
        // toward it rather than in the step through its doorway. Read rather than advanced —
        // LightTexturePortalCrossingMixin owns that ease, and has already stepped it this rebuild.
        return ClientPortalRoomSky.advance(camera.x, camera.y, camera.z,
            ClientPortalCrossing.current());
    }
}
