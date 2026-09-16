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

    /** The locale Dungeon Train is written in, and the one operator edits are authored against. */
    public static final String SOURCE_LOCALE = "en_us";

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

    /**
     * The relay pool a client displaying {@code locale} should fetch and apply.
     *
     * <p>For a translatable locale that is the locale itself. For everything else — the English
     * family and the joke locales, which all render Dungeon Train's English because the mod ships
     * nothing for them — it is {@code en_us}, the source pool an OPERATOR authors into. That pool
     * is how a reworded advancement title or a corrected hint reaches players who are already on a
     * shipped jar, instead of waiting for the next release.</p>
     *
     * <p>Deliberately a separate question from {@link #isTranslatableLocale}, which still says no
     * to English and so still keeps the in-game editor — and with it the player submit path —
     * closed to it. Nobody translates English; only the operator writes it.</p>
     *
     * @return the pool's locale code, or {@code ""} when there is nothing to fetch
     */
    public static String poolLocaleFor(String locale) {
        if (locale == null || locale.isBlank()) {
            return "";
        }
        String code = locale.trim().toLowerCase(java.util.Locale.ROOT);
        return isTranslatableLocale(code) ? code : SOURCE_LOCALE;
    }

    /**
     * The lang-key prefixes that belong to the build editor — the world-space menus, the X menu,
     * the builder profile, the block-variant / container / prefab tools — so a translator can take
     * or leave that body on its own. It is creative-only UI, dense with jargon, and roughly a
     * third of every string the mod ships; a player translating the game they play should not
     * have to wade through it, and a builder translating the tool they use should not have to
     * wade through the death screens to find it.
     */
    private static final java.util.List<String> EDITOR_KEY_PREFIXES = java.util.List.of(
        "gui.dungeontrain.editor_menu.",
        "gui.dungeontrain.editor_screen.",
        "gui.dungeontrain.editor_settings.",
        "gui.dungeontrain.editor_help.",
        "gui.dungeontrain.builder.",
        "gui.dungeontrain.block_variant.",
        "gui.dungeontrain.template_blocks.",
        "gui.dungeontrain.prefab_save.",
        "gui.dungeontrain.prefab_tab.",
        "gui.dungeontrain.number_input.",
        "gui.dungeontrain.scribble.",
        "gui.dungeontrain.custom_content.");

    /**
     * Whether {@code key} is one of the build editor's strings — see {@link #EDITOR_KEY_PREFIXES}.
     * A prefix rule, so a new editor screen keyed under one of those roots is filed without a
     * release; a screen under a new root needs adding here to be.
     */
    public static boolean isEditorKey(String key) {
        if (key == null) {
            return false;
        }
        for (String prefix : EDITOR_KEY_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code unit} is one of the build editor's lang lines — books never are. */
    public static boolean isEditor(TranslationUnit unit) {
        return unit != null && unit.type() == TranslationUnit.Type.LANG && isEditorKey(unit.id());
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
     * Whether this string still wants a human — the "Needs a human" queue: machine translation
     * nobody has reviewed ({@link TranslationUnit#aiUnreviewed()}), or a line whose English was
     * edited after it was last translated or reviewed ({@link TranslationUnit#sourceChanged()}).
     * Either way, nobody has confirmed it says what the English says now.
     *
     * <p>Provenance only knows what was true when the jar
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
        return unit != null && (unit.aiUnreviewed() || unit.sourceChanged())
            && overrideOf(unit, approved) == null;
    }

    /**
     * Whether the English behind {@code unit} moved on after it was last translated or reviewed,
     * and nobody has since approved a replacement. An approved override was written against the
     * English of ITS moment (the relay keeps that text, and the repo import refuses one the
     * English has since left behind), so it answers the newer question and clears the flag.
     */
    public static boolean sourceChanged(TranslationUnit unit, TranslationEdits approved) {
        return unit != null && unit.sourceChanged() && overrideOf(unit, approved) == null;
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
}
