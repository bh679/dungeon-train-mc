package games.brennan.dungeontrain.client.survey;

/**
 * Things players ask for in feedback that the game already has, or that are already on the roadmap.
 * Each gets a note from Brennan after the answer ({@link KnownFeatureDetector}): a death-screen page,
 * or {@link KnownFeatureNoteScreen} after {@code /feedback}.
 *
 * <p>Picked from two months of relay feedback (2026-09-14 to 2026-11-12), most asked first. Table
 * order is the order the notes are shown in.</p>
 */
public enum KnownFeature {
    /** A low NPS score that blames AI. Matched by {@link AiMentionDetector}, not by text alone. */
    AI("ai", Action.AI_POLICY),
    /** Two months of "let me save", which is coming. */
    SAVE_QUIT("save_quit", Action.ROADMAP),
    /** Edible Backpacks. The note hints at the bigger ones and never gives the recipe. */
    BACKPACK("backpack", Action.NONE),
    /** Coming: official multiplayer support. */
    MULTIPLAYER("multiplayer", Action.ROADMAP),
    /** Nemo's Inventory Sorting: in the pack, shipped switched off. */
    SORTING("sorting", Action.NONE),
    /** TrashSlot: in the pack and on, with its slot hidden and every key unbound. */
    TRASH_SLOT("trash_slot", Action.KEY_BINDS),
    /** Just Enough Items: in the pack, shipped switched off. */
    JEI("jei", Action.NONE);

    /** The button beside Back / Next. */
    public enum Action { NONE, AI_POLICY, KEY_BINDS, ROADMAP }

    private final String id;
    private final Action action;

    KnownFeature(String id, Action action) {
        this.id = id;
        this.action = action;
    }

    /** Lang-key segment and log id. */
    public String id() {
        return id;
    }

    public Action action() {
        return action;
    }
}
