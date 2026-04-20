package games.brennan.dungeontrain.train;

import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Produces randomised {@link CarriageSpec}s for a train. Architecture and
 * contents are re-rolled per carriage; style is re-rolled once every
 * {@link #STYLE_RUN_LENGTH} carriages so neighbouring carriages visually
 * group into style "runs" — per the product brief, style should not flicker
 * every carriage.
 *
 * <p>A FLATBED + ENEMIES combo would drop mobs off the open floor, so the
 * generator re-rolls contents once if that combo surfaces.
 */
public final class CarriageSpecGenerator {

    public static final int STYLE_RUN_LENGTH = 10;

    private CarriageSpecGenerator() {}

    public static List<CarriageSpec> generate(int count, RandomSource rng) {
        if (count < 1) throw new IllegalArgumentException("count must be >= 1, got " + count);

        CarriageArchitecture[] architectures = CarriageArchitecture.values();
        CarriageContents[] contentsValues = CarriageContents.values();
        CarriageStyle[] styles = CarriageStyle.values();

        int runCount = (count + STYLE_RUN_LENGTH - 1) / STYLE_RUN_LENGTH;
        CarriageStyle[] runStyles = new CarriageStyle[runCount];
        for (int r = 0; r < runCount; r++) {
            runStyles[r] = styles[rng.nextInt(styles.length)];
        }

        List<CarriageSpec> specs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            CarriageArchitecture arch = architectures[rng.nextInt(architectures.length)];
            CarriageContents contents = contentsValues[rng.nextInt(contentsValues.length)];

            if (arch == CarriageArchitecture.FLATBED && contents == CarriageContents.ENEMIES) {
                contents = contentsValues[rng.nextInt(contentsValues.length)];
            }

            CarriageStyle style = runStyles[i / STYLE_RUN_LENGTH];
            specs.add(new CarriageSpec(arch, style, contents));
        }
        return specs;
    }
}
