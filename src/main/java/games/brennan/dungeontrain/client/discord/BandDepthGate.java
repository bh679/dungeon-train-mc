package games.brennan.dungeontrain.client.discord;

/**
 * Keeps a band off the Discord card until the player is deep enough in to earn its advancement: the same
 * {@link #DEPTH_BLOCKS} the server's band triggers use ({@code BandAdvancements.ENTRY_DEPTH_BLOCKS}).
 * Until then the card keeps showing the last place it named.
 *
 * <p>Immutable: each {@link #next} returns the gate for the following second. Distance is measured along X
 * from where the F3 band first changed, either way, so riding backwards gates the same.</p>
 *
 * @param pendingId the band the player is in now (as {@link PresenceSnapshot#effectiveDimensionId} gives it)
 * @param entryX    the X where {@code pendingId} was first seen
 * @param shownId   the id the card names, or {@code null} before anything has been named
 */
public record BandDepthGate(String pendingId, double entryX, String shownId) {

    /** Mirrors {@code BandAdvancements.ENTRY_DEPTH_BLOCKS}; {@code DiscordRichPresenceTest} keeps them equal. */
    public static final int DEPTH_BLOCKS = 400;

    public static final BandDepthGate EMPTY = new BandDepthGate(null, 0, null);

    /**
     * The gate after the player is seen at {@code x} in {@code id}. {@code gated} is false where there is
     * nothing to keep secret (a real Nether or End dimension): those are named at once.
     */
    public BandDepthGate next(String id, double x, boolean gated) {
        boolean moved = !id.equals(pendingId);
        String pending = id;
        double entry = moved ? x : entryX;
        boolean firstPlainOverworld = shownId == null && PresenceSnapshot.OVERWORLD.equals(id);
        boolean deepEnough = Math.abs(x - entry) >= DEPTH_BLOCKS;
        String shown = !gated || firstPlainOverworld || deepEnough ? id : shownId;
        return new BandDepthGate(pending, entry, shown);
    }
}
