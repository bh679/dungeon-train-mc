package games.brennan.dungeontrain.worldgen.density;

/**
 * Marks the overworld's {@code MultiNoiseBiomeSource} (mixed in by {@code MultiNoiseBiomeSourceMixin}).
 *
 * <p>An identity check against the published source is not enough: TerraBlender fills every chunk's
 * biomes through a fresh {@code Object.clone()} of the source. The mark is a plain field, so the
 * shallow clone carries it and DT's biome override still recognises the overworld.</p>
 */
public interface OverworldBiomeSourceMark {

    void dungeontrain$markOverworld();

    boolean dungeontrain$isOverworld();
}
