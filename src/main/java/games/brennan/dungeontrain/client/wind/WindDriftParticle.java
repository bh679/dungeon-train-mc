package games.brennan.dungeontrain.client.wind;

/**
 * Added to every {@code Particle} by {@code ParticleWindDriftMixin}: the particle's wind weight (set once as it
 * is created, 0 = the wind never moves it) and the per-tick drift step ({@link AmbientWind}).
 */
public interface WindDriftParticle {

    void dungeontrain$setWindWeight(float weight);

    /** Called once per particle tick, after the particle's own tick. */
    void dungeontrain$windDrift();
}
