package games.brennan.dungeontrain.train.id;

/**
 * A world's four-letter mark, the {@code QXRT} in {@code QXRT 4821-K}: a hash of the world's train
 * generation seed, written as letters the way a railway reporting mark is.
 *
 * <p>It is not an identity. Two worlds with the same seed share a mark (they also share every
 * carriage), and 26<sup>4</sup> = 456,976 marks means different seeds can collide. It only lets a
 * rebuild confirm that the seed it was handed is the seed the id came from.</p>
 */
public final class WorldMark {

    /** "DTcarMk1": decorrelates the mark from the generation seed's other uses. */
    private static final long SALT = 0x445463617224D6B1L;
    private static final int LETTERS = 4;

    private WorldMark() {}

    /** The mark of the world whose train generation seed is {@code generationSeed}. */
    public static String of(long generationSeed) {
        long h = generationSeed ^ SALT;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        long n = Long.remainderUnsigned(h, 26L * 26 * 26 * 26);
        char[] out = new char[LETTERS];
        for (int i = LETTERS - 1; i >= 0; i--) {
            out[i] = (char) ('A' + (int) (n % 26));
            n /= 26;
        }
        return new String(out);
    }
}
