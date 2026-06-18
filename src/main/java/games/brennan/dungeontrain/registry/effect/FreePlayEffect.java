package games.brennan.dungeontrain.registry.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * "Free Play" — a permanent, run-scoped marker effect applied while the player's
 * current run is unranked (see
 * {@link games.brennan.dungeontrain.cheat.RunIntegrity}). It is purely cosmetic:
 * no gameplay tick, no attributes. It exists so the top-right status HUD shows the
 * player their run is Free Play, and its inventory hover tooltip (see
 * {@link games.brennan.dungeontrain.client.FreePlayTooltip}) explains the
 * consequence — advancements earned here aren't saved to the profile and stats
 * don't count toward global totals.
 */
public final class FreePlayEffect extends MobEffect {

    /** Soft teal tint for the HUD swirl. */
    private static final int PARTICLE_COLOUR = 0x5BC8C2;

    public FreePlayEffect() {
        super(MobEffectCategory.NEUTRAL, PARTICLE_COLOUR);
    }

    /** Marker effect — never ticks anything. */
    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return false;
    }
}
