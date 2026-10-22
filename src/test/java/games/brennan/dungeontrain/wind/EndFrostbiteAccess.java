package games.brennan.dungeontrain.wind;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;

/** Test bridge to {@link EndFrostbite}'s package-private pace helpers from other test packages. */
public final class EndFrostbiteAccess {

    private EndFrostbiteAccess() {}

    public static long blocksIn(WorldGenCycle cycle, int x) {
        return EndFrostbite.blocksIn(cycle, x);
    }
}
