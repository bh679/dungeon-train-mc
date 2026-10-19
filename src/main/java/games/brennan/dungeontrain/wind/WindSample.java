package games.brennan.dungeontrain.wind;

/**
 * The wind at one place and moment.
 *
 * @param strength   horizontal push, blocks/tick² at multiplier 1 (0 = calm)
 * @param headingRad direction the wind blows TOWARD in the XZ plane: x = cos, z = sin
 * @param gust01     how much of the current push is a gust, 0..1 (drives the strong-wind visuals)
 */
public record WindSample(double strength, double headingRad, double gust01) {

    public static final WindSample CALM = new WindSample(0.0, 0.0, 0.0);

    /** This wind with its strength multiplied by {@code f} (calm at 0; above 1 strengthens it). */
    public WindSample scaled(double f) {
        if (f == 1.0) return this;
        double s = strength * Math.max(0.0, f);
        return s <= 0.0 ? CALM : new WindSample(s, headingRad, gust01);
    }

    public boolean isCalm() {
        return strength <= 0.0;
    }

    public double dirX() {
        return Math.cos(headingRad);
    }

    public double dirZ() {
        return Math.sin(headingRad);
    }
}
