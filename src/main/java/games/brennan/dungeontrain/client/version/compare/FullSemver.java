package games.brennan.dungeontrain.client.version.compare;

import java.util.Comparator;
import java.util.Optional;

/**
 * Strict three-segment {@code MAJOR.MINOR.PATCH} version, compared numerically on all three.
 *
 * <p>Distinct from {@link games.brennan.dungeontrain.client.version.SemverCompare}, which ignores
 * PATCH on purpose so the title-screen badge does not nag during the auto-release cascade. Here
 * the question is "how many releases apart are these", and every release is a distinct value, so
 * nothing is ignored. A leading {@code v} and trailing {@code +build} metadata are tolerated; anything
 * else that is not {@code digits.digits.digits} is rejected rather than guessed at.</p>
 */
public record FullSemver(int major, int minor, int patch) implements Comparable<FullSemver> {

    private static final Comparator<FullSemver> ORDER = Comparator
            .comparingInt(FullSemver::major)
            .thenComparingInt(FullSemver::minor)
            .thenComparingInt(FullSemver::patch);

    public static Optional<FullSemver> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String s = text.strip();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        // Build metadata ("0.52.0+neoforge-1.21.1") is ignorable by definition; the siblings' uploads carry it.
        int plus = s.indexOf('+');
        if (plus >= 0) {
            s = s.substring(0, plus);
        }
        String[] parts = s.split("\\.", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(new FullSemver(
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public int compareTo(FullSemver o) {
        return ORDER.compare(this, o);
    }

    public boolean isNewerThan(FullSemver o) {
        return compareTo(o) > 0;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
