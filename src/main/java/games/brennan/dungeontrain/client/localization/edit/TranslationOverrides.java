package games.brennan.dungeontrain.client.localization.edit;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.mixin.client.I18nAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
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
     * Whether {@code locale} is the one currently installed over the running game.
     *
     * <p>False means the editor is working on a language the client is not displaying — the
     * dev-build case where the UI stays English so it stays readable while the translation being
     * fixed is, say, Chinese. Edits still save and still submit; they just cannot be seen applied,
     * because the game is not rendering that language.</p>
     */
    public static boolean isLive(String locale) {
        return locale != null && locale.equals(locale());
    }

    /** This player's edits for {@code locale} — the live layer, or read from disk if detached. */
    public static TranslationEdits localFor(String locale) {
        return isLive(locale)
            ? local()
            : TranslationOverrideStore.load(TranslationOverrideStore.Layer.LOCAL, locale);
    }

    /** Both layers merged for {@code locale}, local winning. */
    public static TranslationEdits mergedFor(String locale) {
        if (isLive(locale)) {
            return merged();
        }
        return TranslationOverrideStore.load(TranslationOverrideStore.Layer.APPROVED, locale)
            .mergedWith(TranslationOverrideStore.load(TranslationOverrideStore.Layer.LOCAL, locale));
    }

    /** Set (or clear) one lang override for {@code locale}. Applies live only when it is live. */
    public static boolean setLangFor(String locale, String key, String value) {
        if (isLive(locale)) {
            return setLang(key, value);
        }
        return saveDetached(locale, TranslationOverrideStore
            .load(TranslationOverrideStore.Layer.LOCAL, locale).withLang(key, value));
    }

    /** Set (or clear) one book-field override for {@code locale}. */
    public static boolean setBookFor(String locale, String id, String value) {
        if (isLive(locale)) {
            return setBook(id, value);
        }
        return saveDetached(locale, TranslationOverrideStore
            .load(TranslationOverrideStore.Layer.LOCAL, locale).withBook(id, value));
    }

    /** Replace the whole local layer for {@code locale} — the import and revert-all paths. */
    public static boolean replaceLocalFor(String locale, TranslationEdits edits) {
        TranslationEdits next = edits == null ? TranslationEdits.empty(locale) : edits;
        return isLive(locale) ? replaceLocal(next) : saveDetached(locale, next);
    }

    /**
     * Only the edits the relay has not been sent yet — what a Submit should actually carry.
     *
     * <p>Compares the local layer against the snapshot taken at the last Submit. A value the
     * player has since changed again counts as unsubmitted even though its key was sent before;
     * one they have reverted does not, because an override that no longer exists is not
     * outstanding work.</p>
     *
     * <p>The relay dedupes by content hash anyway, so resending was never going to create
     * duplicate rows — but sending two hundred unchanged strings to have the server throw away
     * a hundred and ninety-nine of them is wasted bandwidth on both ends, and it makes the "N
     * edit(s) ready to send" count in front of the player a lie.</p>
     */
    public static TranslationEdits unsubmittedFor(String locale) {
        TranslationEdits local = localFor(locale);
        if (local.isEmpty()) {
            return local;
        }
        TranslationEdits sent = TranslationOverrideStore.load(
            TranslationOverrideStore.Layer.SUBMITTED, locale);
        return new TranslationEdits(locale,
            onlyChanged(local.lang(), sent.lang()), onlyChanged(local.books(), sent.books()));
    }

    /** Whether anything is outstanding — the cue the Submit button turns green on. */
    public static boolean hasUnsubmittedChanges(String locale) {
        return !unsubmittedFor(locale).isEmpty();
    }

    private static Map<String, String> onlyChanged(Map<String, String> local,
                                                   Map<String, String> sent) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : local.entrySet()) {
            if (!entry.getValue().equals(sent.get(entry.getKey()))) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    /**
     * Record that {@code sent} has been handed to the outbox, on top of whatever was already
     * submitted before.
     *
     * <p>Takes what actually went rather than snapshotting the whole local layer, because a
     * submission is capped at the relay's per-call unit limit. Marking everything sent when only
     * the first two hundred went would strand the remainder as permanently "already submitted",
     * and a translator would have no way to tell.</p>
     *
     * <p>Written at queue time rather than on delivery: the outbox owns delivery and retries, and
     * the player has done their part. The screen still reports anything left waiting to send, so
     * nothing is hidden by this.</p>
     */
    public static void markSubmitted(String locale, TranslationEdits sent) {
        if (sent == null || sent.isEmpty()) {
            return;
        }
        TranslationEdits previous = TranslationOverrideStore.load(
            TranslationOverrideStore.Layer.SUBMITTED, locale);
        TranslationOverrideStore.save(
            TranslationOverrideStore.Layer.SUBMITTED, previous.mergedWith(sent));
    }

    /** Write a locale the overlay is not showing. No install — there is nothing to install onto. */
    private static boolean saveDetached(String locale, TranslationEdits edits) {
        return TranslationOverrideStore.save(TranslationOverrideStore.Layer.LOCAL, edits);
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
