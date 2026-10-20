package games.brennan.dungeontrain.client.wind;

/**
 * Implemented on Windy's streak particle by {@code WindyParticleTwoSidedMixin}: how many times to draw it once the
 * wind wants more streaks than {@link WindyDrive#MAX_DENSITY} allows, and the seed that places the extra copies.
 * Lives outside the mixin package so DT code can reference it.
 */
public interface WindyStreakCopies {

    void dungeontrain$setCopies(int copies, long seed);
}
