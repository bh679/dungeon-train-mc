package games.brennan.dungeontrain.client.localization.edit;

/**
 * The editor's list predicates, kept free of {@code Screen}/{@code Minecraft} so what a translator
 * is shown can be reasoned about — and unit-tested — on its own.
 *
 * <p>Lives apart from {@link TranslationScreen} for the same reason the submit payload does: the
 * question "does this string still need a human?" is worth being able to answer without opening
 * Minecraft.</p>
 */
public final class TranslationFilters {

    private TranslationFilters() {}

    /** This layer's override for {@code unit}, or null — the two bodies are keyed separately. */
    public static String overrideOf(TranslationUnit unit, TranslationEdits edits) {
        if (unit == null || edits == null) {
            return null;
        }
        return unit.type() == TranslationUnit.Type.BOOK
            ? edits.books().get(unit.id())
            : edits.lang().get(unit.id());
    }

    /**
     * Whether this string still wants a human — the "Needs a human" queue.
     *
     * <p>Provenance ({@link TranslationUnit#aiUnreviewed()}) only knows what was true when the jar
     * was built. An operator approving a player's fix on the relay IS the human review it was
     * waiting for, so an approved string leaves the queue: asking the next volunteer to redo work
     * that has already been done and released is the fastest way to waste the goodwill this
     * feature runs on.</p>
     *
     * <p>Deliberately the APPROVED layer alone, never the merged one. This player's own unsubmitted
     * edit is work in progress, not review — it stays visible here (marked as theirs) and is what
     * the separate "still to do" filter subtracts.</p>
     */
    public static boolean needsHuman(TranslationUnit unit, TranslationEdits approved) {
        return unit != null && unit.aiUnreviewed() && overrideOf(unit, approved) == null;
    }
}
