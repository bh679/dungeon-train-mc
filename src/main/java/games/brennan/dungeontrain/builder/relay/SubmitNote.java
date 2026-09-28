package games.brennan.dungeontrain.builder.relay;

/**
 * What an author tells the reviewer when submitting a build: how its redstone works, what its loot
 * is, and anything else. The first two are only asked when the build has them ({@code SubmitHints});
 * {@code notes} is the question that is always asked. Any may be empty — the note is optional.
 */
public record SubmitNote(String redstone, String loot, String notes) {

    /** Nothing to tell the reviewer — a withdraw, or a submit with every box left blank. */
    public static final SubmitNote EMPTY = new SubmitNote("", "", "");

    public SubmitNote {
        redstone = redstone == null ? "" : redstone;
        loot = loot == null ? "" : loot;
        notes = notes == null ? "" : notes;
    }

    /** Only the general answer — what a note was before the redstone and loot questions existed. */
    public static SubmitNote of(String notes) {
        return new SubmitNote("", "", notes);
    }

    public boolean isEmpty() {
        return redstone.isEmpty() && loot.isEmpty() && notes.isEmpty();
    }

    /** Every field made safe to forward — see {@link BuilderRelayUpload#cleanNote}. */
    public SubmitNote cleaned() {
        return new SubmitNote(BuilderRelayUpload.cleanNote(redstone), BuilderRelayUpload.cleanNote(loot),
                BuilderRelayUpload.cleanNote(notes));
    }
}
