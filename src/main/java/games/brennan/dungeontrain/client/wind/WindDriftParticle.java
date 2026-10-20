package games.brennan.dungeontrain.client.wind;

/**
 * Added to every {@code Particle} by {@code ParticleWindDriftMixin}: the particle's wind weight (set once as it
 * is created, 0 = the wind never moves it) and the per-tick drift step ({@link AmbientWind}).
 */
public interface WindDriftParticle {

    void dungeontrain$setWindWeight(float weight);

    /** Start the drift at the wind's full speed instead of easing into it (particles DT spawns already blowing). */
    void dungeontrain$primeDrift();

    /** Called once per particle tick, after the particle's own tick. */
    void dungeontrain$windDrift();
}
