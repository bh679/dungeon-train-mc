package games.brennan.dungeontrain.client;

import java.util.ArrayList;
import java.util.List;

/**
 * The three tabs {@link DungeonTrainClientOptionsScreen} files its rows under, and which one the
 * player is currently looking at.
 *
 * <p>Same split-by-subject shape as the editor menu's {@link games.brennan.dungeontrain.client.menu.EditorMenuTab},
 * for the same reason: eleven rows in one list is a wall. The division is by <em>what the row acts
 * on</em>:</p>
 *
 * <ul>
 *   <li>{@link #GENERAL} — the client and the player: content rating, chat lines, the hotkey,
 *       translation.</li>
 *   <li>{@link #TRAIN} — the ride itself: engine volume, whether custom train content loads, and
 *       the ride-photo settings.</li>
 *   <li>{@link #EDITOR} — the three display-scale channels the in-world editor menus and HUD
 *       render at.</li>
 * </ul>
 *
 * <p>Kept free of Minecraft types on purpose. Two rows are conditional — {@link Row#POLITICAL_FILTER}
 * on Chinese clients, {@link Row#TRANSLATE} when a translation target resolves — and the screen packs
 * rows two-across, so either one appearing re-pairs everything after it in its tab. That pairing is
 * the part most likely to break silently and the part a headless test can actually reach, which it
 * cannot do through live widgets.</p>
 */
public enum ClientOptionsTab {

    GENERAL("general"),
    TRAIN("train"),
    EDITOR("editor");

    private final String key;

    ClientOptionsTab(String key) {
        this.key = key;
    }

    /** Translation key for this tab's label on the navigation bar. */
    public String titleKey() {
        return "gui.dungeontrain.options.tab." + key;
    }

    /**
     * Opens on General: it holds the rows a player is most often hunting for, and the other two are
     * for people who already know what they came to change.
     *
     * <p>Client-only state, so it lives here as a static — the same shape as
     * {@link games.brennan.dungeontrain.client.menu.EditorMenuTab#active()}. It deliberately persists
     * across closing and reopening the screen: someone adjusting editor scales should not be sent
     * back to General every time they reopen Options.</p>
     */
    private static ClientOptionsTab active = GENERAL;

    public static ClientOptionsTab active() {
        return active;
    }

    public static void select(ClientOptionsTab tab) {
        if (tab != null) active = tab;
    }

    /** One row of the options screen — identity only; the screen decides what widget renders it. */
    public enum Row {
        // --- General ---
        CONTENT_MODE,
        /** Chinese-language clients only — absent, not merely inert, everywhere else. */
        POLITICAL_FILTER,
        BOOK_AUTHOR_CHAT,
        CINEMATIC_HOTKEY,
        /** Only when {@code TranslationTarget.resolveForClient()} names a language to edit. */
        TRANSLATE,

        // --- Train ---
        TRAIN_VOLUME,
        CUSTOM_CONTENT,
        SNAPSHOT_MAX_RES,
        SNAPSHOT_CHAT_LOG,

        // --- Editor ---
        SCALE_ALL,
        SCALE_WORLDSPACE,
        SCALE_HUD
    }

    /**
     * The rows in this tab, in render order, for a client with the given two conditional rows.
     *
     * <p>Every tab is non-empty in all four combinations — no combination of flags can produce a tab
     * that opens onto nothing.</p>
     */
    public static List<Row> rowsFor(ClientOptionsTab tab, boolean chineseLocale, boolean hasTranslateTarget) {
        List<Row> rows = new ArrayList<>();
        switch (tab) {
            case GENERAL -> {
                rows.add(Row.CONTENT_MODE);
                if (chineseLocale) {
                    rows.add(Row.POLITICAL_FILTER);
                }
                rows.add(Row.BOOK_AUTHOR_CHAT);
                rows.add(Row.CINEMATIC_HOTKEY);
                if (hasTranslateTarget) {
                    rows.add(Row.TRANSLATE);
                }
            }
            case TRAIN -> {
                rows.add(Row.TRAIN_VOLUME);
                rows.add(Row.CUSTOM_CONTENT);
                rows.add(Row.SNAPSHOT_MAX_RES);
                rows.add(Row.SNAPSHOT_CHAT_LOG);
            }
            case EDITOR -> {
                rows.add(Row.SCALE_ALL);
                rows.add(Row.SCALE_WORLDSPACE);
                rows.add(Row.SCALE_HUD);
            }
        }
        return List.copyOf(rows);
    }
}
