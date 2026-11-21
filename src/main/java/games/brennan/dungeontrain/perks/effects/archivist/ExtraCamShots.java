package games.brennan.dungeontrain.perks.effects.archivist;

/**
 * Extra Cam-Shots: each rank is half an extra shot per camera, given out in turn rather than by chance.
 * Every two ranks are one extra shot on every camera; an odd rank's half gives one more shot to every
 * second camera this life, starting with the first. Rank 1: +1 on cameras 1, 3, 5 ...; rank 2: +1 on
 * every camera; rank 3: +1 on every camera and +2 on cameras 1, 3, 5 ... Decided once per camera, when
 * it takes its first shot. Pure.
 */
public final class ExtraCamShots {

    /** Extra shots per camera per rank, on average. Balancing knob (the chips show it). */
    public static final float CHANCE_PER_RANK = 0.5f;

    private ExtraCamShots() {}

    /**
     * Extra shots for a camera at {@code rank}, where {@code cameraIndex} is how many cameras this player
     * has already shot with this life (0 for the first). 0 for rank 0 or less.
     */
    public static int extraShots(int rank, int cameraIndex) {
        if (rank <= 0) return 0;
        int whole = rank / 2;
        boolean half = rank % 2 == 1;
        return whole + (half && Math.floorMod(cameraIndex, 2) == 0 ? 1 : 0);
    }
}
