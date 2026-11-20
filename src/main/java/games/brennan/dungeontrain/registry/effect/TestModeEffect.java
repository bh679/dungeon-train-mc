package games.brennan.dungeontrain.registry.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * "Test" — a permanent marker effect shown in a perks test world ({@link
 * games.brennan.dungeontrain.perks.PerksGate#isTestWorld}). Test mode is live play with perks on, kept
 * off the live record ({@link games.brennan.dungeontrain.cheat.RunIntegrity#isOffRecord}) and saved to
 * its own profile. Purely cosmetic, like {@link FreePlayEffect}: it tells the player which mode they
 * are in, and doubles as the client's signal that the run is off the record (the live-stream gates).
 * Its hover tooltip is {@link games.brennan.dungeontrain.client.TestModeTooltip}.
 */
public final class TestModeEffect extends MobEffect {

    /** Dark red, matching the title screen's perks test button. */
    private static final int PARTICLE_COLOUR = 0x8C1414;

    public TestModeEffect() {
        // BENEFICIAL so it sits in the top row of the status HUD beside Free Play.
        super(MobEffectCategory.BENEFICIAL, PARTICLE_COLOUR);
    }

    /** Marker effect — never ticks anything. */
    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return false;
    }
}
