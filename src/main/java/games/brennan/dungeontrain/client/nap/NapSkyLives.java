package games.brennan.dungeontrain.client.nap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * What the nap sky knows of the player's perk history: the paths of past lives that have a perk record
 * (oldest first), this life's path so far, and whether perks are on in this world. With perks off the
 * sky is drawn as it always was: every past life random, this life regrowing. Immutable.
 *
 * <p>{@link #plan} decides, per past-life slot, which kind of path it is; pure, so it is unit-tested.</p>
 */
public record NapSkyLives(List<NapSkyPaths.Path> records, NapSkyPaths.Path thisLife, boolean perksOn) {

    /** The sky as it was before perks: nothing recorded, nothing activated. */
    public static final NapSkyLives NONE = new NapSkyLives(List.of(), null, false);

    /** How a past-life slot is drawn. */
    public enum Kind {
        /** A first life's passing try: traced, then gone. */
        TRY,
        /** A past life, perks off: random, kept at half. */
        PAST,
        /** A past life before perks, perks on: random, kept at half, then gone once this life settles. */
        PRE_PERKS,
        /** A past life with a perk record: its real path, kept at half. */
        REAL
    }

    public NapSkyLives {
        records = records == null ? List.of() : List.copyOf(records);
    }

    /** Build from perk ids: each past life's activated perks, and this life's. */
    public static NapSkyLives of(NapSky sky, NapSkyStars stars, List<? extends Collection<String>> pastLives,
                                 Collection<String> thisLife, boolean perksOn) {
        if (!perksOn || sky == null || stars == null) return NONE;
        List<NapSkyPaths.Path> records = new ArrayList<>();
        for (Collection<String> life : pastLives) records.add(NapSkyPaths.fromStars(sky, stars.stars(life)));
        NapSkyPaths.Path now = NapSkyPaths.fromStars(sky, stars.stars(thisLife));
        return new NapSkyLives(records, now.edges().isEmpty() ? null : now, true);
    }

    /** Whether this life has activated perks to draw (else it keeps regrowing at random). */
    public boolean hasThisLife() {
        return perksOn && thisLife != null && !thisLife.edges().isEmpty();
    }

    /**
     * The past-life slots, oldest first, for {@code lifeDeaths} lives counted by the death stats,
     * {@code maxPast} at most. Perks on: as many lives as the larger of the count and the records (a
     * perks test world is Free Play, so its deaths add records but no count); the newest are the
     * recorded ones. No past lives at all: {@code tries} passing tries.
     */
    public static List<Kind> plan(long lifeDeaths, int records, boolean perksOn, int maxPast, int tries) {
        long total = perksOn ? Math.max(Math.max(0L, lifeDeaths), records) : Math.max(0L, lifeDeaths);
        List<Kind> out = new ArrayList<>();
        if (total == 0) {
            for (int i = 0; i < tries; i++) out.add(Kind.TRY);
            return out;
        }
        int drawn = (int) Math.min(total, maxPast);
        int real = perksOn ? Math.min(records, drawn) : 0;
        for (int i = 0; i < drawn - real; i++) out.add(perksOn ? Kind.PRE_PERKS : Kind.PAST);
        for (int i = 0; i < real; i++) out.add(Kind.REAL);
        return out;
    }

    /** Lives counted in total, for numbering the random paths (seeded per life). */
    public static long totalLives(long lifeDeaths, int records, boolean perksOn) {
        return perksOn ? Math.max(Math.max(0L, lifeDeaths), records) : Math.max(0L, lifeDeaths);
    }
}
