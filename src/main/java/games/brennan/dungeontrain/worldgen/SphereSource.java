package games.brennan.dungeontrain.worldgen;

/**
 * Which dimension's world generation a floating sphere in the {@link SpheresBand} is cut from. Pure
 * (no Minecraft types) so {@link SphereField} stays unit-testable; {@code ForeignSphereSampler} maps
 * each value to its level.
 */
public enum SphereSource {
    OVERWORLD,
    NETHER,
    END
}
