package games.brennan.dungeontrain.train;

/**
 * Full per-carriage specification. Architecture governs shape/windows/sizing,
 * style governs block materials, contents governs interior population.
 */
public record CarriageSpec(
    CarriageArchitecture architecture,
    CarriageStyle style,
    CarriageContents contents
) {}
