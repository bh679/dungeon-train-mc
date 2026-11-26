package games.brennan.dungeontrain.perks;

/**
 * The eleven perk classes arranged around The Engine in the perks tree. The order here is the
 * design's list order, not the on-screen lane order (the menu owns that).
 */
public enum PerkClass {
    ARCHIVIST,
    LOVER,
    WARLOCK,
    KNIGHT,
    TANK,
    CRAFTER,
    SPEEDSTER,
    BUILDER,
    HOARDER,
    TRADER,
    ALCHEMIST;

    /** Lower-case id used in command output and lang keys ({@code archivist}). */
    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
