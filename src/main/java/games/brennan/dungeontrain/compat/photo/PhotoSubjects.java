package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.advancement.BandAdvancements;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The subject keys a photo is worth to {@code dungeontrain:photo_subject} criteria — pure, so the
 * vocabulary is pinned by tests and the JSON can rely on it.
 *
 * <ul>
 *   <li>{@code entity:<type id>} — every entity type in frame ({@code entity:minecraft:cow})</li>
 *   <li>{@code playermob}, {@code echo}, {@code selfie_playermob} (a selfie with a PlayerMob in it)</li>
 *   <li>specials: {@code pigman_villager}, {@code killer_bunny}, {@code technoblade_pig}</li>
 *   <li>{@code cave}, {@code dim:<dimension id>}</li>
 *   <li>{@code nether} / {@code end} — the train's own Nether or End, or the vanilla dimension</li>
 *   <li>{@code band:<forward band id>} and {@code band:any} — taken inside a dimensional band</li>
 * </ul>
 */
public final class PhotoSubjects {

    public static final String ENTITY_PREFIX = "entity:";
    public static final String DIMENSION_PREFIX = "dim:";
    public static final String BAND_PREFIX = "band:";
    public static final String BAND_ANY = BAND_PREFIX + "any";
    public static final String PLAYERMOB = "playermob";
    public static final String ECHO = "echo";
    public static final String SELFIE_PLAYERMOB = "selfie_playermob";
    public static final String PIGMAN_VILLAGER = "pigman_villager";
    public static final String KILLER_BUNNY = "killer_bunny";
    public static final String TECHNOBLADE_PIG = "technoblade_pig";
    public static final String NETHER = "nether";
    public static final String END = "end";
    public static final String CAVE = "cave";

    /**
     * What one photo shows, already read off the frame and the live entities in it.
     *
     * @param entityTypes  type ids of every entity in frame
     * @param dimension    dimension id the photo was taken in, or {@code null}
     * @param band         forward band advancement id the photographer stood in, or {@code null}
     */
    public record Facts(Set<String> entityTypes, boolean playerMob, boolean echo, boolean pigmanVillager,
                        boolean killerBunny, boolean technobladePig, boolean selfie, boolean inCave, String dimension, String band) {}

    /** The train's Nethers — the first one and its second, regrown look. */
    private static final Set<String> NETHER_BANDS = Set.of(BandAdvancements.NETHER, BandAdvancements.BETTER_NETHER);

    /** The train's Ends — the floating islands and their blooming look. */
    private static final Set<String> END_BANDS = Set.of(BandAdvancements.END_ISLANDS, BandAdvancements.BETTER_END);

    private PhotoSubjects() {}

    public static Set<String> keys(Facts f) {
        Set<String> out = new LinkedHashSet<>();
        for (String type : f.entityTypes()) out.add(ENTITY_PREFIX + type);
        if (f.playerMob()) out.add(PLAYERMOB);
        if (f.echo()) out.add(ECHO);
        if (f.selfie() && f.playerMob()) out.add(SELFIE_PLAYERMOB);
        if (f.pigmanVillager()) out.add(PIGMAN_VILLAGER);
        if (f.killerBunny()) out.add(KILLER_BUNNY);
        if (f.technobladePig()) out.add(TECHNOBLADE_PIG);
        if (f.inCave()) out.add(CAVE);
        if (f.dimension() != null) out.add(DIMENSION_PREFIX + f.dimension());
        if ("minecraft:the_nether".equals(f.dimension()) || (f.band() != null && NETHER_BANDS.contains(f.band()))) out.add(NETHER);
        if ("minecraft:the_end".equals(f.dimension()) || (f.band() != null && END_BANDS.contains(f.band()))) out.add(END);
        if (f.band() != null) {
            out.add(BAND_PREFIX + f.band());
            out.add(BAND_ANY);
        }
        return Set.copyOf(out);
    }
}
