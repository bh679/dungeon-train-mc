package games.brennan.dungeontrain.client.localization.edit;

import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;

import java.lang.ref.WeakReference;

/**
 * The single owner of the language list's height, so the two things the mod adds to that screen can
 * both take a strip out of it without fighting.
 *
 * <p>{@link LanguageScreenSearch} wants a row above the list; {@link LanguageScreenTranslateButton}
 * wants one below it when its label will not fit on vanilla's footer row. Each is its own
 * {@code ScreenEvent.Init.Post} handler and NeoForge does not order them, so two direct calls to
 * {@code updateSizeAndPosition} means whichever runs second silently discards the other's strip.
 * Both declare their strip here instead, and every declaration re-applies both.</p>
 *
 * <p>Idempotent by construction: the height is always derived from {@code screen.layout}, never from
 * where the list currently sits, so the repeated {@code Init.Post} passes vanilla makes on one
 * screen converge instead of shrinking the list a row at a time. The strips reset when the list
 * object changes, which is what a re-{@code init()} — a resize, a resource reload — produces.</p>
 */
final class LanguageScreenLayout {

    /**
     * Which list the current strips belong to. Weak, because this is static state on a client-thread
     * screen: a hard reference would outlive the screen and hold its whole widget tree.
     */
    private static WeakReference<ObjectSelectionList<?>> owner = new WeakReference<>(null);
    private static int top;
    private static int bottom;

    private LanguageScreenLayout() {}

    /** Reserve {@code px} above the list, for a row drawn at {@link #contentTop}. */
    static void reserveTop(LanguageSelectScreen screen, int px) {
        reserve(screen, px, -1);
    }

    /** Reserve {@code px} below the list. */
    static void reserveBottom(LanguageSelectScreen screen, int px) {
        reserve(screen, -1, px);
    }

    /** Where a reserved top row is drawn: under the title, at the top of the content band. */
    static int contentTop(LanguageSelectScreen screen) {
        return screen.layout.getHeaderHeight();
    }

    /**
     * What the list would be left with if the bottom strip were {@code px}.
     *
     * <p>"were", not "were also": the caller asking is the one that owns the bottom strip, and on
     * the repeated {@code Init.Post} passes vanilla makes it is re-declaring the same reservation
     * rather than adding a second one. Compounding them here is what would make the guard below
     * flip between passes.</p>
     */
    static int heightIfBottom(LanguageSelectScreen screen, int px) {
        rebindIfStale(screen);
        return screen.layout.getContentHeight() - top - px;
    }

    /** The same question for the top strip. */
    static int heightIfTop(LanguageSelectScreen screen, int px) {
        rebindIfStale(screen);
        return screen.layout.getContentHeight() - bottom - px;
    }

    private static void reserve(LanguageSelectScreen screen, int newTop, int newBottom) {
        ObjectSelectionList<?> list = rebindIfStale(screen);
        if (list == null) {
            return;
        }
        if (newTop >= 0) {
            top = newTop;
        }
        if (newBottom >= 0) {
            bottom = newBottom;
        }
        int height = screen.layout.getContentHeight() - top - bottom;
        if (height <= 0) {
            return; // a window too short to hold what has been asked for; leave vanilla's sizing
        }
        list.updateSizeAndPosition(list.getWidth(), height, contentTop(screen) + top);
    }

    /**
     * The screen's list, clearing both strips when it is a different object than the one they were
     * measured against — vanilla builds a fresh list in every {@code addContents}, so a new instance
     * is precisely the signal that the previous pass's reservations no longer apply.
     */
    private static ObjectSelectionList<?> rebindIfStale(LanguageSelectScreen screen) {
        ObjectSelectionList<?> list = LanguageSwitchPrompt.languageList(screen);
        if (list != owner.get()) {
            owner = new WeakReference<>(list);
            top = 0;
            bottom = 0;
        }
        return list;
    }
}
