package games.brennan.dungeontrain.registry.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * "Live" — a marker effect held while the player is streaming with the Live Eye (see
 * {@link games.brennan.dungeontrain.event.LiveEffectEvents}). Purely cosmetic: no gameplay tick, no
 * attributes. Drawn without a timer by {@link games.brennan.dungeontrain.client.UntimedEffectClient}.
 */
public final class LiveEffect extends MobEffect {

    /** Broadcast red for the HUD swirl. */
    private static final int PARTICLE_COLOUR = 0xE0383E;

    public LiveEffect() {
        // BENEFICIAL so the icon sits in the top row of the status area, as Free Play does.
        super(MobEffectCategory.BENEFICIAL, PARTICLE_COLOUR);
    }

    /** Marker effect — never ticks anything. */
    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return false;
    }
}
