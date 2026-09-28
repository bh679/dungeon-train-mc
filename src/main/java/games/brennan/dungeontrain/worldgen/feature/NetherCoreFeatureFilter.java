package games.brennan.dungeontrain.worldgen.feature;

/**
 * Which live placed features the Nether band's core may place for a given core biome.
 *
 * <p>Other mods add features to the <b>vanilla</b> Nether biomes: BetterNether, through WorldWeaver
 * {@code biome_modifications}, puts its cincinnasite/ruby/lapis/redstone ores into nether_wastes,
 * crimson/warped forest, soul sand valley and basalt deltas. In the real Nether dimension those edits
 * stay. The vanilla-style Nether band is meant to look like the vanilla Nether, though. So a
 * {@code minecraft:} core biome places only {@code minecraft:} features plus Dungeon Train's own. A
 * non-vanilla core biome (the BetterNether passes) places its full live list, unchanged.</p>
 *
 * <p>The caller skips a rejected feature <b>before</b> it takes a feature-seed index. That keeps the
 * chunk's seed sequence the same as it is in a world without the injecting mod.</p>
 */
final class NetherCoreFeatureFilter {

    static final String VANILLA_NAMESPACE = "minecraft";
    static final String DT_NAMESPACE = "dungeontrain";

    private NetherCoreFeatureFilter() {}

    /**
     * True when a feature from {@code featureNamespace} may place in a core biome from
     * {@code biomeNamespace}. An unknown (null) namespace on either side is allowed, so an unkeyed
     * holder keeps today's behaviour and never drops decoration by accident.
     */
    static boolean allowed(String biomeNamespace, String featureNamespace) {
        if (!VANILLA_NAMESPACE.equals(biomeNamespace) || featureNamespace == null) return true;
        return VANILLA_NAMESPACE.equals(featureNamespace) || DT_NAMESPACE.equals(featureNamespace);
    }
}
