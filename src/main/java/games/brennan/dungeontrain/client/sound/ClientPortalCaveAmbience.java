package games.brennan.dungeontrain.client.sound;

/**
 * When the next cave noise should play in a dimensional carriage — the timer behind
 * {@link PortalCaveAmbienceEvents}, and the third rule to read the region
 * {@link ClientPortalTrainAudio} caches.
 *
 * <p><b>Why a timer of our own.</b> Vanilla's {@code BiomeAmbientSoundsHandler} builds its
 * "moodiness" only from blocks whose sky light <i>and</i> block light are both zero, and needs some
 * 6000 ticks of them to fire once. A dimensional carriage is under the bedrock, so the sky half is satisfied;
 * the light half never is, because the rooms and corridors are deliberately lit — {@code
 * PortalBuilder} stamps ceiling lights into the shell, {@code PortalCarriageBuilder} floors the
 * crossing zone with sea lanterns. Moodiness therefore <i>falls</i> the whole time a player is down
 * there, and vanilla will essentially never speak. The room decides instead of the light.</p>
 *
 * <p><b>Armed on entry, not fired on it.</b> The gap is rolled when the player enters the room and
 * counted down while they stay, so stepping through the doorway never lands a noise on the same
 * tick — which would read as a cue for the doorway rather than atmosphere in the room. Leaving
 * disarms, so a partly-elapsed gap does not carry across a visit to the train and back.</p>
 *
 * <p>Every tiled copy of an endless room counts as the same room, which is what
 * {@link ClientPortalTrainAudio#inRoom} answers, so walking between copies does not restart the
 * count.</p>
 *
 * <p>Pure logic, no Minecraft imports, so it unit-tests without a NeoForge bootstrap.</p>
 */
public final class ClientPortalCaveAmbience {

    /** Shortest gap between two noises, in ticks (30 s). */
    public static final int MIN_GAP_TICKS = 600;

    /** Longest gap between two noises, in ticks (180 s). */
    public static final int MAX_GAP_TICKS = 3600;

    /** Ticks left before the next noise, or {@link #DISARMED} when the player is not in a room. */
    private static final int DISARMED = -1;

    private static int countdown = DISARMED;

    private ClientPortalCaveAmbience() {}

    /**
     * Advance one tick and say whether a cave noise should play now.
     *
     * @param inRoom whether the player is in a dimensional carriage this tick
     * @param roll   a fresh value in {@code [0, 1)}, used to pick a gap whenever one is armed
     * @return {@code true} on the tick the gap runs out, at which point the next one is already
     *         rolled
     */
    public static boolean tick(boolean inRoom, double roll) {
        if (!inRoom) {
            countdown = DISARMED;
            return false;
        }
        if (countdown == DISARMED) {
            countdown = gapFrom(roll);
            return false;
        }
        if (--countdown > 0) return false;

        countdown = gapFrom(roll);
        return true;
    }

    /** Forget everything. Wired to logging out, alongside the region cache's own reset. */
    public static void reset() {
        countdown = DISARMED;
    }

    /** The rolled gap, uniform across {@link #MIN_GAP_TICKS}..{@link #MAX_GAP_TICKS}. */
    static int gapFrom(double roll) {
        double clamped = roll < 0.0 ? 0.0 : (roll >= 1.0 ? Math.nextDown(1.0) : roll);
        return MIN_GAP_TICKS + (int) (clamped * (MAX_GAP_TICKS - MIN_GAP_TICKS + 1));
    }
}
