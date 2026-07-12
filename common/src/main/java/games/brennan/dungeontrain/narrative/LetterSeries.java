package games.brennan.dungeontrain.narrative;

/**
 * One assignment from a player's current-life letter series: the opaque per-life
 * {@code seriesId} (regenerated each life — a new life is a new series) and the 1-based
 * {@code letterIndex} of the letter within that series.
 *
 * <p>Produced by {@link NarrativeProgressData#nextLetter(java.util.UUID, long)} at sign time and
 * carried to the relay by {@code discord.LetterReporter}. The series is tied to the life, not to a
 * lectern: letters signed on different lecterns during one life share one {@code seriesId} with an
 * ascending {@code letterIndex}.</p>
 */
public record LetterSeries(String seriesId, int letterIndex) {}
