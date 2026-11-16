package games.brennan.dungeontrain.client.discord;

import java.util.Objects;

/**
 * What the player's Discord card shows: one line under "Dungeon Train" and the small icon beside the logo.
 * Immutable, so the client thread can hand it to the IPC worker as-is; the worker sends it only when it
 * differs from the last one sent.
 *
 * <p>Deliberately nothing else: no singleplayer/multiplayer, no difficulty, no timer. Only the three
 * vanilla places are ever named; every other band (and a dimensional carriage) reads as
 * {@link Place#OTHER}, so a player's Discord friends learn nothing about where the line goes.</p>
 */
public record PresenceSnapshot(Place place, String details) {

    /** Where the player is, as far as Discord is told. {@code imageKey} is the Developer Portal art asset. */
    public enum Place {
        MENU("menu", "logo"),
        OVERWORLD("overworld", "overworld"),
        NETHER("nether", "nether"),
        END("end", "end"),
        OTHER("other", "other");

        private final String token;
        private final String imageKey;

        Place(String token, String imageKey) {
            this.token = token;
            this.imageKey = imageKey;
        }

        /** The lang key for the line under "Dungeon Train". */
        public String langKey() {
            return "dungeontrain.discord_status." + token;
        }

        public String imageKey() {
            return imageKey;
        }
    }

    public PresenceSnapshot {
        Objects.requireNonNull(place, "place");
        Objects.requireNonNull(details, "details");
    }

    /**
     * The place for a dimension id as the F3 screen prints it ({@code minecraft:the_nether},
     * {@code dungeontrain:upside_down}, {@code dungeontrain:dimensional_carriage}, ...). Only vanilla's
     * three ids are named; anything else, another mod's dimension included, is {@link Place#OTHER}.
     */
    public static Place placeFor(String dimensionId) {
        if (dimensionId == null) return Place.OTHER;
        return switch (dimensionId) {
            case "minecraft:overworld" -> Place.OVERWORLD;
            case "minecraft:the_nether" -> Place.NETHER;
            case "minecraft:the_end" -> Place.END;
            default -> Place.OTHER;
        };
    }

    /**
     * The id to classify: the real dimension unless the player is in the overworld, where the band (or a
     * dimensional carriage) stands in for it exactly as on the F3 screen. An empty band id means
     * "no band", i.e. plain overworld.
     */
    public static String effectiveDimensionId(String realDimensionId, String bandId, boolean inDimensionalCarriage,
                                              String dimensionalCarriageId) {
        if (!"minecraft:overworld".equals(realDimensionId)) return realDimensionId;
        if (inDimensionalCarriage) return dimensionalCarriageId;
        return bandId == null || bandId.isEmpty() ? realDimensionId : bandId;
    }
}
