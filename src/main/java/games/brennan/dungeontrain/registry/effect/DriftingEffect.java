package games.brennan.dungeontrain.registry.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * "Drifting" — a marker effect held while the player is inside a drifting carriage or drifting
 * room (see {@link games.brennan.dungeontrain.event.DriftingEffectEvents}), the places whose
 * changes travel the shared-carriage relay to other worlds. Purely cosmetic: no gameplay tick, no
 * attributes. Its inventory row (name only, no duration) is drawn by
 * {@link games.brennan.dungeontrain.client.DriftingEffectClient}; its tooltip line by
 * {@link games.brennan.dungeontrain.client.DriftingTooltip}.
 */
public final class DriftingEffect extends MobEffect {

    /** Pale violet tint for the HUD swirl, apart from Free Play's teal and Warmth's blue. */
    private static final int PARTICLE_COLOUR = 0xB59CFF;

    public DriftingEffect() {
        // BENEFICIAL so the icon sits in the top row of the status area, as Free Play does.
        super(MobEffectCategory.BENEFICIAL, PARTICLE_COLOUR);
    }

    /** Marker effect — never ticks anything. */
    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return false;
    }
}
