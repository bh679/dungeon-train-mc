package games.brennan.dungeontrain.client.discord;

import games.brennan.dungeontrain.worldgen.TrainPhase;

import java.util.Objects;

/**
 * What the player's Discord card shows: one line under "Dungeon Train" and the small icon beside the logo.
 * Immutable, so the client thread can hand it to the IPC worker as-is; the worker sends it only when it
 * differs from the last one sent.
 *
 * <p>Deliberately nothing else: no singleplayer/multiplayer, no difficulty. The line is the place as the F3
 * screen names it: the three vanilla places, or a band's own name ({@code gui.dungeontrain.editor_menu.phase.*},
 * already in every locale). A band only shows once the player is deep enough in to earn its advancement
 * ({@link BandDepthGate}).</p>
 *
 * @param imageKey the Developer Portal art asset for the small icon, or {@code null} for none
 */
public record PresenceSnapshot(String imageKey, String details) {

    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";
    public static final String END = "minecraft:the_end";
    private static final String BAND_PREFIX = "dungeontrain:";
    private static final String STATUS_KEY = "dungeontrain.discord_status.";
    private static final String BAND_NAME_KEY = "gui.dungeontrain.editor_menu.phase.";

    public PresenceSnapshot {
        Objects.requireNonNull(details, "details");
    }

    /**
     * Where the player is, as far as Discord is told: the lang key for the line and the art asset for the
     * small icon ({@code null} on the title screen, which shows only the logo).
     */
    public record Place(String langKey, String imageKey) {
        public static final Place MENU = new Place(STATUS_KEY + "menu", null);
        /** A dimensional carriage, another mod's dimension, or anywhere not yet deep enough to name. */
        public static final Place OTHER = new Place(STATUS_KEY + "other", "other");
    }

    /**
     * The place for a dimension id as the F3 screen prints it ({@code minecraft:the_nether},
     * {@code dungeontrain:upside_down}, ...). Vanilla's three ids and every train band are named; anything
     * else (a dimensional carriage, another mod's dimension) is {@link Place#OTHER}.
     */
    public static Place placeFor(String dimensionId) {
        if (dimensionId == null) return Place.OTHER;
        switch (dimensionId) {
            case OVERWORLD: return new Place(STATUS_KEY + "overworld", "overworld");
            case NETHER: return new Place(STATUS_KEY + "nether", "nether");
            case END: return new Place(STATUS_KEY + "end", "end");
            default: break;
        }
        if (dimensionId.startsWith(BAND_PREFIX)) {
            String token = dimensionId.substring(BAND_PREFIX.length());
            TrainPhase phase = TrainPhase.byToken(token);
            if (phase != null && phase.token().equals(token)) {
                return new Place(BAND_NAME_KEY + token, "other");
            }
        }
        return Place.OTHER;
    }

    /**
     * The id to classify: the real dimension unless the player is in the overworld, where the band (or a
     * dimensional carriage) stands in for it exactly as on the F3 screen. An empty band id means
     * "no band", i.e. plain overworld.
     */
    public static String effectiveDimensionId(String realDimensionId, String bandId, boolean inDimensionalCarriage,
                                              String dimensionalCarriageId) {
        if (!OVERWORLD.equals(realDimensionId)) return realDimensionId;
        if (inDimensionalCarriage) return dimensionalCarriageId;
        return bandId == null || bandId.isEmpty() ? realDimensionId : bandId;
    }
}
