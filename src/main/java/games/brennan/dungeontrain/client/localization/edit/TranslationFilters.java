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

    /**
     * Vanilla locales that are English wearing a hat but do not carry the {@code en_} prefix.
     * LOLCAT is its own code. See {@link #isTranslatableLocale}.
     */
    private static final java.util.Set<String> JOKE_LOCALES = java.util.Set.of("lol_us");

    private TranslationFilters() {}

    /**
     * Whether {@code locale} is a language Dungeon Train could be translated into at all.
     *
     * <p>Deliberately NOT "a language the mod already ships": that rule made the nineteen locales
     * with a lang file the only ones anybody could work on, so a player whose language had nothing
     * was shown no way to start it. Every real language is translatable; what it has already is a
     * separate question, and the one the editor answers by showing blank strings.</p>
     *
     * <p>What stays excluded is the English family and the joke locales — {@code en_us} itself,
     * {@code en_au}, {@code en_gb}, {@code en_pt} (Pirate), {@code en_ud} (upside down),
     * {@code lol_us}. Those render English by vanilla's own fallback whatever the mod does, so
     * there is genuinely nothing there to translate and submissions against them could never be
     * applied. A prefix rule rather than a list of codes, so a locale Mojang adds to the family
     * later is covered without a release.</p>
     */
    public static boolean isTranslatableLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return false;
        }
        String code = locale.trim().toLowerCase(java.util.Locale.ROOT);
        return !code.startsWith("en_") && !JOKE_LOCALES.contains(code);
    }

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

    /**
     * The same question, minus the strings this player has already read and marked
     * <b>good as is</b>.
     *
     * <p>A dismissal is a review — a speaker of the language went through the line and decided it
     * needed nothing — so it leaves the queue exactly as an approval does. It differs in who it
     * binds: an approval releases text to everybody, a dismissal only ever changes what THIS
     * translator is shown, which is why it lives on their disk and not in the served pool.</p>
     *
     * @param dismissed this locale's dismissals, or null to ask the unfiltered question
     */
    public static boolean needsHuman(TranslationUnit unit, TranslationEdits approved,
                                     java.util.function.Predicate<TranslationUnit> dismissed) {
        return needsHuman(unit, approved) && (dismissed == null || !dismissed.test(unit));
    }

    /**
     * What this locale currently shows for {@code unit}, before this player's own pending work —
     * the approved override if somebody's fix has been released, otherwise what the jar ships.
     *
     * <p>The APPROVED layer, never the merged one, for the same reason {@link #needsHuman} uses it:
     * every question below is about whether the LANGUAGE still needs this string done, and a
     * translator's own unsaved edit is the work, not the answer to it.</p>
     */
    private static String currentText(TranslationUnit unit, TranslationEdits approved) {
        String override = overrideOf(unit, approved);
        return override != null ? override : unit.shipped();
    }

    /** Whether nothing has been written here at all — no shipped text, no approved fix. */
    public static boolean untranslated(TranslationUnit unit, TranslationEdits approved) {
        if (unit == null) {
            return false;
        }
        String current = currentText(unit, approved);
        return current == null || current.isBlank();
    }

    /**
     * Whether this locale's text is still the English source, word for word.
     *
     * <p>Usually a line that was copied across and never touched. Sometimes it is genuinely correct
     * — a proper noun, a bare {@code %s} — and the queue has an answer for that already: the
     * translator marks it <b>good as is</b> and {@link TranslationDismissals} takes it out for
     * good. Better to ask once about a name than to leave a whole file of untouched English
     * invisible.</p>
     *
     * <p>Requires a source to compare against: the sibling mods' units carry {@code source = ""}
     * because their English lives in their own repos, and without this guard every one of them
     * would match on the empty string.</p>
     */
    public static boolean sameAsSource(TranslationUnit unit, TranslationEdits approved) {
        if (unit == null || unit.source() == null || unit.source().isBlank()) {
            return false;
        }
        String current = currentText(unit, approved);
        return current != null && current.trim().equals(unit.source().trim());
    }

    /**
     * The editor's working queue — everything this language still has left to do.
     *
     * <p>Broader than {@link #needsHuman} on purpose. That one asks the narrow provenance question
     * ("is this machine translation nobody has checked?"), which is the right question for the AI
     * review filter and the wrong one for the queue: a key the locale has never had, and a line
     * still sitting in English, carry no provenance entry at all, so the filter meant to surface
     * the outstanding work was the one place they could not be seen.</p>
     *
     * <p>Dismissals apply here exactly as they do to {@link #needsHuman}: a speaker who has read
     * the line and let it stand has done the review, whichever of the three reasons put it in
     * front of them.</p>
     *
     * @param dismissed this locale's dismissals, or null to ask the unfiltered question
     */
    public static boolean stillToDo(TranslationUnit unit, TranslationEdits approved,
                                    java.util.function.Predicate<TranslationUnit> dismissed) {
        if (unit == null) {
            return false;
        }
        boolean outstanding = needsHuman(unit, approved)
            || untranslated(unit, approved)
            || sameAsSource(unit, approved);
        return outstanding && (dismissed == null || !dismissed.test(unit));
    }
}
