package games.brennan.dungeontrain.train;

/**
 * What populates the interior of a carriage. Placed lazily when a player
 * comes within {@link ContentsPopulator#PROXIMITY_BLOCKS} of the carriage
 * centre.
 */
public enum CarriageContents {
    EMPTY,
    STORAGE,
    ENEMIES,
    NARRATIVE
}
