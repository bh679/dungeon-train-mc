package games.brennan.dungeontrain.compat.mixinguard;

import java.util.Map;

/**
 * Which mod each position-readout hook needs ({@code PositionReadoutMixinPlugin}), by the sub-package
 * of {@code mixin.client.positionreadout} the hook sits in. Outside the mixin package, so unit tests
 * can read it; mixin forbids referencing classes in a mixin package directly.
 */
public final class PositionReadoutMods {

    /** Sub-package → the real mod id the loader reports (not the store slug). */
    private static final Map<String, String> MOD_BY_PACKAGE = Map.of(
        "xaerominimap", "xaerominimap",
        "xaeroworldmap", "xaeroworldmap",
        "betterf3", "betterf3",
        "jade", "jade",
        "sodiumextra", "sodium_extra");

    static final String PREFIX = "games.brennan.dungeontrain.mixin.client.positionreadout.";

    private PositionReadoutMods() {}

    /** The mod id a hook in that config needs; {@code null} for anything else, which then never applies. */
    public static String modFor(String mixinClassName) {
        if (mixinClassName == null || !mixinClassName.startsWith(PREFIX)) return null;
        String rest = mixinClassName.substring(PREFIX.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? null : MOD_BY_PACKAGE.get(rest.substring(0, dot));
    }
}
