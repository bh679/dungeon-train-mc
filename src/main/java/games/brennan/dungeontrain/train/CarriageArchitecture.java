package games.brennan.dungeontrain.train;

/**
 * Shell shape of a single carriage — walls, windows, roof, sizing. One of
 * three orthogonal carriage properties along with {@link CarriageStyle}
 * (block materials) and {@link CarriageContents} (what's inside).
 */
public enum CarriageArchitecture {
    STANDARD,
    WINDOWED,
    SOLID_ROOF,
    FLATBED
}
