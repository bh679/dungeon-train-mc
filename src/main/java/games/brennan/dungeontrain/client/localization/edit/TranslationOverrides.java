package games.brennan.dungeontrain.client.localization.edit;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.mixin.client.I18nAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import org.slf4j.Logger;

import java.util.Map;

/**
 * The live translation-override layer: what the player has edited (and what the relay has
 * approved) for the language they are playing in, applied to the running client.
 *
 * <p>Precedence, highest first: this player's own edits, then relay-approved translations for
 * the locale, then whatever the jar and resource packs shipped. A translator must always see
 * their own text — an approved translation arriving for a key they are mid-way through editing
 * must not overwrite what is in front of them.</p>
 *
 * <p>Lang keys apply here, client-side and immediately. Book fields do not: narrative prose is
 * datapack content the server owns, so {@link TranslationEdits#books()} is carried by this class
 * for the editor and the submit path but consumed on the integrated server instead.</p>
 *
 * <p>Client-thread only. Every entry point is no-throw — a broken override file must never be
 * able to stop the game from having a language.</p>
 */
public final class TranslationOverrides {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The locale these edits belong to; empty until {@link #reload} has run. */
    private static String locale = "";
    private static TranslationEdits local = TranslationEdits.empty("");
    private static TranslationEdits approved = TranslationEdits.empty("");
    private static TranslationEdits merged = TranslationEdits.empty("");

    private TranslationOverrides() {}

    /** The locale currently loaded, or {@code ""} before the first {@link #reload}. */
    public static synchronized String locale() {
        return locale;
    }

    /** This player's own edits for the active locale. Never null. */
    public static synchronized TranslationEdits local() {
        return local;
    }

    /** Relay-approved overrides for the active locale, as of the last fetch. Never null. */
    public static synchronized TranslationEdits approved() {
        return approved;
    }

    /** Both layers merged, local winning. Never null. */
    public static synchronized TranslationEdits merged() {
        return merged;
    }

    /**
     * The effective translation for {@code key} — the override if there is one, else what the
     * game would otherwise show. Used by the editor to render the "current" column without
     * having to know which layer supplied it.
     */
    public static String effectiveLang(String key) {
        String override = merged().lang().get(key);
        return override != null ? override : Language.getInstance().getOrDefault(key, key);
    }

    /**
     * Re-read both layers for {@code newLocale} from disk and install the result.
     *
     * <p>Called on startup and after every resource reload / language switch — the language the
     * overlay wraps is rebuilt from scratch each time, so the overlay has to be too.</p>
     */
    public static void reload(String newLocale) {
        String target = newLocale == null ? "" : newLocale;
        synchronized (TranslationOverrides.class) {
            locale = target;
            local = TranslationOverrideStore.load(TranslationOverrideStore.Layer.LOCAL, target);
            approved = TranslationOverrideStore.load(TranslationOverrideStore.Layer.APPROVED, target);
            merged = approved.mergedWith(local);
        }
        install();
    }

    /** Re-read both layers for whatever locale the client is currently set to. */
    public static void reloadForCurrentLocale() {
        Minecraft mc = Minecraft.getInstance();
        String code = mc != null && mc.options != null ? mc.options.languageCode : null;
        reload(code);
    }

    /**
     * Set (or, with a blank value, clear) one lang override, persist it, and apply it now.
     *
     * @return true when the edit was stored
     */
    public static boolean setLang(String key, String value) {
        TranslationEdits updated;
        synchronized (TranslationOverrides.class) {
            updated = local.withLang(key, value);
            if (updated.lang().equals(local.lang())) {
                return true; // no change — nothing to write, nothing to reinstall
            }
        }
        return apply(updated);
    }

    /**
     * Set (or clear) one book-field override. Book prose is server-owned, so this persists the
     * edit and then asks the integrated server to reload — in single-player that makes the change
     * visible without leaving the world; on a remote server it is a no-op.
     */
    public static boolean setBook(String id, String value) {
        TranslationEdits updated;
        synchronized (TranslationOverrides.class) {
            updated = local.withBook(id, value);
            if (updated.books().equals(local.books())) {
                return true;
            }
        }
        if (!apply(updated)) {
            return false;
        }
        IntegratedProseReload.requestIfHosting();
        return true;
    }

    /** Replace this player's whole local layer at once — the import and revert-all paths. */
    public static boolean replaceLocal(TranslationEdits edits) {
        return apply(edits == null ? TranslationEdits.empty(locale()) : edits);
    }

    /**
     * Replace the relay-approved layer. Separate from {@link #replaceLocal} so a fetch can never
     * clobber a player's in-progress work, and so the two survive in separate files.
     */
    public static boolean replaceApproved(TranslationEdits edits) {
        TranslationEdits next = edits == null ? TranslationEdits.empty(locale()) : edits;
        if (!TranslationOverrideStore.save(TranslationOverrideStore.Layer.APPROVED, next)) {
            return false;
        }
        synchronized (TranslationOverrides.class) {
            approved = next;
            merged = approved.mergedWith(local);
        }
        install();
        return true;
    }

    private static boolean apply(TranslationEdits updated) {
        if (!TranslationOverrideStore.save(TranslationOverrideStore.Layer.LOCAL, updated)) {
            return false;
        }
        synchronized (TranslationOverrides.class) {
            local = updated;
            merged = approved.mergedWith(local);
        }
        install();
        return true;
    }

    /**
     * Install the current merged overrides over the game's language.
     *
     * <p>Wraps the overlay's {@link OverlayLanguage#delegate() delegate} when one is already
     * installed, so repeated edits replace the overlay instead of stacking a new one on every
     * keystroke. With no overrides left the delegate goes back in bare, which also restores
     * exactly what vanilla had.</p>
     *
     * <p>{@code I18n} keeps its own reference to the language rather than reading
     * {@code Language.getInstance()}, so it has to be pointed at the overlay separately — miss
     * that and every {@code I18n.get} call site silently ignores overrides.</p>
     */
    private static void install() {
        try {
            Map<String, String> overrides = merged().lang();
            Language current = Language.getInstance();
            Language base = current instanceof OverlayLanguage overlay ? overlay.delegate() : current;
            if (base == null) {
                return;
            }
            Language next = overrides.isEmpty() ? base : new OverlayLanguage(base, overrides);
            Language.inject(next);
            I18nAccessor.dungeontrain$setLanguage(next);
            LOGGER.debug("[DungeonTrain] Translations: installed {} override(s) for '{}'.",
                overrides.size(), locale());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Translations: failed to install overrides — {}", t.toString());
        }
    }
}
