package games.brennan.dungeontrain.train;

/** Test access to {@link TrainAssembler#seedNumbering} from other packages. */
public final class TrainSeedNumberingProbe {
    private TrainSeedNumberingProbe() {}

    /** The number the carriage at local index {@code localPIdx} gets on a train seeded with {@code pIdxBase}. */
    public static int landingPIdx(int localPIdx, int pIdxBase, int groupSize) {
        return TrainAssembler.seedNumbering(localPIdx, pIdxBase, groupSize).initialPIdx();
    }
}
