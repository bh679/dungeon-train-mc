package games.brennan.dungeontrain.client.localization.edit;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * Which language the translation editor should open on.
 *
 * <p>For a player that is simply whatever they are playing in — you translate into the language
 * you speak, and English has no target because it is the source.</p>
 *
 * <p>On a <b>dev build</b> it also answers "and if they are on English?". Testing the editor
 * otherwise means switching the whole game to Chinese, which makes every button in the editor
 * unreadable to whoever is testing it. So a dev build keeps the UI in English and points the
 * editor at {@link #DEFAULT_DEV_TARGET} instead — the editor already keys everything on its
 * target locale rather than the display locale, so nothing else has to know.</p>
 *
 * <p>Dev-only by design: on a release build an English player has nothing to translate and no
 * button appears.</p>
 */
public final class TranslationTarget {

    /** Where a dev build points the editor when the game itself is in English. */
    public static final String DEFAULT_DEV_TARGET = "zh_cn";

    /**
     * Override for the dev target, mirroring the {@code DUNGEONTRAIN_RELAY_BASE_URL} seam — unset
     * in normal use.
     */
    private static final String ENV_OVERRIDE = "DUNGEONTRAIN_TRANSLATE_TARGET";

    private TranslationTarget() {}

    /**
     * The locale to edit, or {@code ""} when there is nothing to offer.
     *
     * @param displayLocale the locale the client is actually rendering in
     */
    public static String resolve(String displayLocale) {
        if (TranslationScreen.isEditable(displayLocale)) {
            return displayLocale;
        }
        return DungeonTrain.isDevBuild() ? devTarget() : "";
    }

    /** The locale to edit for the running client. */
    public static String resolveForClient() {
        Minecraft mc = Minecraft.getInstance();
        String display = mc != null && mc.getLanguageManager() != null
            ? mc.getLanguageManager().getSelected() : null;
        return resolve(display);
    }

    /** Whether anything is offered at all — what the button and the options row gate on. */
    public static boolean isAvailable(String displayLocale) {
        return !resolve(displayLocale).isEmpty();
    }

    private static String devTarget() {
        String override = System.getenv(ENV_OVERRIDE);
        if (override != null && !override.isBlank()) {
            String code = override.trim().toLowerCase(Locale.ROOT);
            if (TranslationScreen.isEditable(code)) {
                return code;
            }
        }
        return DEFAULT_DEV_TARGET;
    }
}
