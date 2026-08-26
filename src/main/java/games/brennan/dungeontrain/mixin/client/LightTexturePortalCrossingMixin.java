package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.client.ClientPortalCrossing;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import org.joml.Vector3f;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds the world lightmap at a constant while the camera is in the middle of a portal corridor, so
 * that being swapped between the corridor and its twin cannot change what is drawn.
 *
 * <p>The fifth sibling of {@link LightTextureUpsideDownBandMixin},
 * {@link LightTextureEndBandMixin}, {@link LightTextureNetherBandMixin} and
 * {@link LightTexturePortalRoomMixin}, and the plainest of the five: one hook, no tint of its own
 * and no sky to pin. Where the other four are pretending the camera stands somewhere it does not,
 * this one is only trying to make two places look alike.</p>
 *
 * <h2>Why a constant is the whole trick</h2>
 * <p>A pair's carriage and its twin are stamped block for block from one source, but the carriage's
 * train-side door is real and the twin's is a plugged dummy, so light leaks into one and not the
 * other — and the swap fires on <i>facing</i> ({@code PortalFacing}), anywhere along the corridor,
 * including a block inside the train door where that difference is largest. The existing defence is
 * to floor the crossing zone with light-15 lanterns so leakage cannot move a value already at
 * maximum, which works for the built-in geometry and is only a convention in the authored templates
 * players actually walk through.</p>
 *
 * <p>Lifting the lightmap's floor toward a fixed white by a fixed amount says nothing about where
 * the camera is, so it lands identically in both copies. Ramped in by {@code PortalCrossingLight} —
 * nothing at either door plane, full from the baffles inward — it fades the corridor's own lighting
 * out as the player walks in and back in as they leave, and across the middle, where the swap
 * happens, there is nothing left for the swap to change.</p>
 *
 * <p><b>A floor lift, not a wash.</b> {@code Vector3f.lerp} toward white raises the dark end of the
 * range and leaves what is already bright where it is, so a lit corridor keeps its own character and
 * only its shadows come up. Full strength is {@link #DUNGEONTRAIN_CROSSING_LIFT}, well short of 1 —
 * the aim is to make the two copies indistinguishable, not to white the corridor out.</p>
 *
 * <p>Purely client-side and per-player, like all four siblings: no server state, no saved-world
 * change, and nothing the engine actually stores. Self-disables ({@code t == 0}) outside every
 * corridor, whenever the server stops sending ({@link ClientPortalCrossing} expires it), and
 * whenever the player has switched the effect off.</p>
 *
 * <p>Shares its target method with the four others. A portal corridor inside a band would have both
 * handlers apply and compose, which is harmless — both are lifts — and does not arise in practice,
 * since a corridor rides the train and the bands are measured off world X.</p>
 */
@Mixin(LightTexture.class)
public abstract class LightTexturePortalCrossingMixin {

    /** Neutral white: the crossing is not somewhere else, it is the same corridor lit flatter. */
    @Unique
    private static final Vector3f DUNGEONTRAIN_CROSSING_TINT = new Vector3f(1.0F, 1.0F, 1.0F);

    /**
     * Max floor-lift at full ramp — the tuning knob for this whole feature.
     *
     * <p>Heavier than the bands' {@code 0.25} and deliberately so: those are giving a mood to a
     * place, and this has to swamp a real difference between two places. Short of {@code 1.0} by
     * enough that the corridor still reads as a lit stone box rather than as fog.</p>
     */
    @Unique
    private static final float DUNGEONTRAIN_CROSSING_LIFT = 0.55F;

    /**
     * Crossing intensity for the in-progress lightmap rebuild, captured once at the head of the
     * method and reused by the per-cell lift — so the ease advances once per rebuild rather than 256
     * times, the same arrangement {@link LightTexturePortalRoomMixin} documents.
     */
    @Unique
    private float dungeontrain$crossingT;

    /**
     * Advance the ease, once, before vanilla starts filling cells.
     *
     * <p>At {@code HEAD} rather than riding an expression the way the four siblings do. They have to
     * hook {@code getSkyDarken} because they modify it; this one does not pin daylight at all, and
     * borrowing that seam only to ignore its value would tie the ease to a call that could move.</p>
     *
     * <p>Advanced even while the effect is switched off, so that turning it back on resumes from
     * now rather than from a value left over from the last corridor.</p>
     */
    @Inject(method = "updateLightTexture(F)V", at = @At("HEAD"))
    private void dungeontrain$advanceCrossing(float partialTicks, CallbackInfo ci) {
        float t = ClientPortalCrossing.advance();
        this.dungeontrain$crossingT =
            ClientDisplayConfig.isPortalCrossingFadeEnabled() ? t : 0.0F;
    }

    /**
     * Floor: after vanilla has combined block and sky light for this cell, lift it toward white by
     * the ramp, so both copies of the corridor resolve to the same picture across the crossing.
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
    private void dungeontrain$liftCrossingFloor(float partialTicks, CallbackInfo ci,
                                                @Local(ordinal = 1) Vector3f cellColor) {
        float t = this.dungeontrain$crossingT;
        if (t <= 0.0F) return;
        cellColor.lerp(DUNGEONTRAIN_CROSSING_TINT, DUNGEONTRAIN_CROSSING_LIFT * t);
    }
}
