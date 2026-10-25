package games.brennan.dungeontrain.perks;

/**
 * The nine tier-1 perks, one per class that has one at "requires balancing". Ids are the
 * kebab-case slugs of the design names and are what {@link Perks#rank} is keyed by.
 * Class sessions add their tier 2/3 perks beside these in their own classes.
 */
public final class TierOnePerks {

    public static final String HEALTH = "health";                           // Tank
    public static final String SPEED = "speed";                             // Speedster
    public static final String DAMAGE = "damage";                           // Knight
    public static final String STACKABLE_BOOK_QUILL = "stackable-book-quill"; // Archivist
    public static final String FRIENDLINESS = "friendliness";               // Lover
    public static final String SEE_TRADE_VALUES = "see-trade-values";       // Trader
    public static final String BIGGER_BACKPACKS = "bigger-backpacks";       // Hoarder
    public static final String CRAFT_LEVEL_STATS = "craft-level-stats";     // Crafter
    public static final String DRIFT_CHANCE = "drift-chance";               // Builder

    static {
        PerkRegistry.register(HEALTH, PerkClass.TANK, 1);
        PerkRegistry.register(SPEED, PerkClass.SPEEDSTER, 1);
        PerkRegistry.register(DAMAGE, PerkClass.KNIGHT, 1);
        PerkRegistry.register(STACKABLE_BOOK_QUILL, PerkClass.ARCHIVIST, 1);
        PerkRegistry.register(FRIENDLINESS, PerkClass.LOVER, 1);
        PerkRegistry.register(SEE_TRADE_VALUES, PerkClass.TRADER, 1);
        PerkRegistry.register(BIGGER_BACKPACKS, PerkClass.HOARDER, 1);
        PerkRegistry.register(CRAFT_LEVEL_STATS, PerkClass.CRAFTER, 1);
        PerkRegistry.register(DRIFT_CHANCE, PerkClass.BUILDER, 1);
    }

    private TierOnePerks() {}

    /** Forces class initialisation (and so registration). */
    static void ensureRegistered() {
        // static block did the work
    }
}
