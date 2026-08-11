package games.brennan.dungeontrain.client.localization.edit;

import games.brennan.dungeontrain.mixin.client.LanguageSelectEntryAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.network.chat.Component;

/**
 * Bridges the gap between the language you have <em>highlighted</em> on the vanilla language screen
 * and the one the game is actually running in.
 *
 * <p>Vanilla treats the list selection as pending — nothing is applied until Done — so a player who
 * clicks a row and then clicks Help Translate would otherwise be handed an editor for the language
 * they were just leaving. That is precisely backwards for the player this doorway is for: the one who
 * noticed their language reads badly and reached for a better one. So when the two disagree, we ask
 * before opening anything.</p>
 *
 * <p>Nothing is asked when they agree, which is the ordinary case, nor when the highlighted row is a
 * language with nothing to translate — English is the source, and a question with no useful answer is
 * worse than no question.</p>
 */
public final class LanguageSwitchPrompt {

    private LanguageSwitchPrompt() {}

    /**
     * Open the editor from the language screen, asking about the pending selection first if it
     * differs from the language in use.
     *
     * @param target the locale the editor would open on with no switch — see {@link TranslationTarget}
     */
    public static void openEditor(LanguageSelectScreen screen, String target) {
        Minecraft mc = Minecraft.getInstance();
        LanguageManager languages = mc.getLanguageManager();
        String applied = languages.getSelected();
        String picked = selectedCode(screen);

        if (picked == null || picked.equals(applied) || !TranslationScreen.isEditable(picked)) {
            mc.setScreen(new TranslationScreen(screen, target));
            return;
        }

        Component pickedName = languageName(languages, picked);
        Component appliedName = languageName(languages, applied);
        mc.setScreen(new ConfirmScreen(
            switchLanguage -> {
                if (switchLanguage) {
                    apply(mc, languages, picked);
                    mc.setScreen(new TranslationScreen(screen, picked));
                } else {
                    mc.setScreen(new TranslationScreen(screen, target));
                }
            },
            Component.translatable("gui.dungeontrain.translate.switch.title"),
            Component.translatable("gui.dungeontrain.translate.switch.message", pickedName, appliedName),
            Component.translatable("gui.dungeontrain.translate.switch.yes", pickedName),
            Component.translatable("gui.dungeontrain.translate.switch.no", appliedName)) {

            @Override
            public void onClose() {
                // Vanilla's default closes to NO screen at all, which would drop the player out of
                // the menus entirely. Backing out of a question should put you back where you asked.
                mc.setScreen(screen);
            }
        });
    }

    /** Exactly what {@code LanguageSelectScreen#onDone} does, minus its return to the last screen. */
    private static void apply(Minecraft mc, LanguageManager languages, String code) {
        languages.setSelected(code);
        mc.options.languageCode = code;
        // Options are written out by OptionsSubScreen#removed as we leave the language screen.
        mc.reloadResourcePacks();
    }

    /** The highlighted row's locale, or {@code null} if there is no list or no selection. */
    private static String selectedCode(LanguageSelectScreen screen) {
        ObjectSelectionList<?> list = languageList(screen);
        if (list == null) {
            return null;
        }
        // Object, not AbstractSelectionList.Entry — the entry type is protected, and all we want from
        // it is the code the accessor mixin exposes.
        Object selected = list.getSelected();
        return selected instanceof LanguageSelectEntryAccessor entry ? entry.dungeontrain$code() : null;
    }

    /**
     * The language list itself, or {@code null} if another mod has replaced it — in which case both
     * callers fall back to what the button did before there was a list to consult.
     */
    static ObjectSelectionList<?> languageList(LanguageSelectScreen screen) {
        for (var child : screen.children()) {
            if (child instanceof ObjectSelectionList<?> list) {
                return list;
            }
        }
        return null;
    }

    /** Vanilla's own localized name for a language, falling back to the bare code. */
    private static Component languageName(LanguageManager languages, String code) {
        LanguageInfo info = languages.getLanguage(code);
        return info != null ? info.toComponent() : Component.literal(code);
    }
}
