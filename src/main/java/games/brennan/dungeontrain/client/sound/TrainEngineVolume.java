package games.brennan.dungeontrain.client.sound;

/**
 * How the player's <b>Train Engine Volume</b> setting folds into the gain {@link TrainEngineSound}
 * works out for itself — the fourth rule in this package, and the only one that comes from a
 * preference rather than from where the player is standing.
 *
 * <p><b>It scales, it does not decide.</b> The distance curve, the portal-corridor rule and the
 * death-screen fade each compute a volume for a position; this multiplies whichever of them spoke.
 * So the shape of every one of them survives — the in-carriage maximum is still the loudest point,
 * the falloff still reaches silence at the same distance — and the setting only says how loud that
 * whole shape is. That is what makes it different from vanilla's Ambient slider, which the engine
 * also passes through: this one moves the train without moving the cave noises
 * {@link PortalCaveAmbienceEvents} plays around it.</p>
 *
 * <p><b>Zero is off, and off is not "very quiet".</b> Every audible result is lifted to
 * {@link #KEEP_ALIVE_FLOOR}, because vanilla's {@code SoundEngine} drops an instance whose gain
 * reaches zero and {@link TrainEngineSound} needs to stay registered across the silent stretches of
 * its own curve — a player 64 blocks from the train has not turned the engine off. A setting of
 * zero has, so it returns a true {@code 0}, and {@link TrainSoundManager} stops the instance
 * outright rather than leaving a streamed channel looping at a gain nobody can hear.</p>
 *
 * <p>Pure logic, no Minecraft imports, so it unit-tests without a NeoForge bootstrap.</p>
 */
public final class TrainEngineVolume {

    /**
     * The smallest gain an audible engine is given.
     *
     * <p>Matches the figure {@link TrainEngineSound} has always clamped its own curve to, and exists
     * for the same reason: {@code SoundEngine.play(...)} skips an instance whose computed volume is
     * {@code <= 0}, so a sound that must survive being inaudible cannot report exactly zero.</p>
     */
    public static final float KEEP_ALIVE_FLOOR = 0.0001f;

    /**
     * Below this the setting counts as off. A guard against float drift in a stored {@code 0.0},
     * not a threshold — the config snaps to tenths, so no real setting lands near it.
     */
    private static final double OFF_EPSILON = 1.0e-6;

    private TrainEngineVolume() {}

    /** Does the player want the engine at all? {@code false} only when the setting is zero. */
    public static boolean audible(double setting) {
        return setting > OFF_EPSILON;
    }

    /**
     * {@code base} scaled by the setting: {@code 0} when the setting is off or the base is already
     * silent, otherwise the product lifted to {@link #KEEP_ALIVE_FLOOR} and capped at {@code 1}.
     *
     * <p>A base of zero stays zero rather than being lifted — a caller passing zero is reporting
     * "nothing to play", not a quiet moment in the curve.</p>
     */
    public static float scale(float base, double setting) {
        if (base <= 0.0f || !audible(setting)) return 0.0f;
        double scaled = base * Math.min(setting, 1.0);
        return (float) Math.max(KEEP_ALIVE_FLOOR, Math.min(scaled, 1.0));
    }
}
