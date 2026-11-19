package games.brennan.dungeontrain.worldgen;

/**
 * The dimension id the debug screen prints for a band, so each band reads as a dimension of its own
 * rather than as one more stretch of {@code minecraft:overworld}.
 *
 * <p>The Nether and End bands borrow vanilla's real ids, since those are the places they stand in for;
 * every other band is {@code dungeontrain:<token>}. Styled occurrences (WWOO, Better Nether, ...) read
 * as their plain band on purpose: this is on every player's screen, and the F3+4 panel is where the
 * style lives ({@link BandLabel}).</p>
 */
public final class BandDimensionName {

    /** What a dimensional carriage reads as: a place apart, whichever band it was entered from. */
    public static final String DIMENSIONAL_CARRIAGE = "dungeontrain:dimensional_carriage";

    private BandDimensionName() {}

    /** The id for {@code phase}. Exhaustive on purpose: a new phase must say which kind of name it takes. */
    public static String of(TrainPhase phase) {
        return switch (phase) {
            case OVERWORLD -> "minecraft:overworld";
            case NETHER -> "minecraft:the_nether";
            case END -> "minecraft:the_end";
            case VOID, UPSIDE_DOWN, CHUNCKS, SPHERES, STACKS, BETA, ALPHA, SKYLANDS, INFDEV, FLOATING,
                 FAR_LANDS, CLASSIC, CAVES_OF_CHAOS, LARGE_BIOMES, AMPLIFIED, LOST_CITY, DEEPER_DARK ->
                "dungeontrain:" + phase.token();
        };
    }
}
