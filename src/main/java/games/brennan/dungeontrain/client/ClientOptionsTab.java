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
 *       backups, the backpack button, translation.</li>
 *   <li>{@link #TRAIN} — the ride itself: engine volume, whether custom train content loads, and
 *       the ride-photo settings.</li>
 *   <li>{@link #EDITOR} — the three display-scale channels the in-world editor menus and HUD
 *       render at, plus where each of the four editor menus (X/V/C/Z) draws itself.</li>
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
        /** Whether Edible Backpacks draws its open/close button on the inventory screen. */
        BACKPACK_BUTTON,
        /** Non-interactive caption introducing the backup rows below it. */
        BACKUPS_HEADING,
        /** Where restore points of builds and progress are written. */
        BACKUPS,
        /** How many archives to keep per Dungeon Train version. */
        BACKUPS_PER_VERSION,
        /** Deletes every archive, in the instance and outside it. Shows the size on disk. */
        CLEAR_BACKUPS,
        /** Opens the AI Policy page. Unconditional — every client can reach it. */
        AI_POLICY,
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
        SCALE_HUD,
        MENU_SPACE_COMMAND,
        MENU_SPACE_TEMPLATE_BLOCKS,
        MENU_SPACE_CONTAINER_CONTENTS,
        MENU_SPACE_BLOCK_VARIANT
    }

    /**
     * The rows in this tab, in render order, for a client with the given two conditional rows.
     *
     * <p>Every tab is non-empty in all four combinations — no combination of flags can produce a tab
     * that opens onto nothing.</p>
     */
    /**
     * Rows that must begin a fresh line rather than pairing with whatever precedes them.
     *
     * <p>Rows are packed two-across in list order, so without this the first row of a group lands
     * beside the last row of the previous one and the group stops reading as a group. Only the
     * LEADER is named — the rest of the group pairs among themselves as usual.</p>
     */
    private static final java.util.Set<Row> GROUP_LEADERS =
            java.util.EnumSet.of(Row.BACKUPS_HEADING, Row.AI_POLICY);

    /**
     * Rows that are captions rather than settings: no widget to operate, and always a line to
     * themselves.
     */
    private static final java.util.Set<Row> HEADINGS = java.util.EnumSet.of(Row.BACKUPS_HEADING);

    /** Whether {@code row} is a caption rather than a setting. */
    public static boolean isHeading(Row row) {
        return HEADINGS.contains(row);
    }

    /** Whether {@code row} begins a visual group. See {@link #GROUP_LEADERS}. */
    public static boolean startsGroup(Row row) {
        return GROUP_LEADERS.contains(row);
    }

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
                rows.add(Row.BACKPACK_BUTTON);
                // The two rows that open a page rather than change a setting, led by the
                // unconditional one so they pair on a line of their own — put TRANSLATE in the
                // lead and the pair breaks apart on the release en_us clients where it is absent.
                rows.add(Row.AI_POLICY);
                if (hasTranslateTarget) {
                    rows.add(Row.TRANSLATE);
                }
                // The backup block goes last, behind its own heading — it is the only group here
                // with enough rows to need one, and the heading is what separates it from the
                // ungrouped settings above.
                rows.add(Row.BACKUPS_HEADING);
                rows.add(Row.BACKUPS);
                // Adjacent so the width packer pairs the two short backup rows on one line.
                rows.add(Row.BACKUPS_PER_VERSION);
                rows.add(Row.CLEAR_BACKUPS);
            }
            case TRAIN -> {
                // The two short-captioned rows lead so they pair on one line; the two whose captions
                // need a full-width row follow. Packing is sequential — it will not reach past a wide
                // row to find a partner — so adjacency here is what puts them side by side.
                rows.add(Row.TRAIN_VOLUME);
                rows.add(Row.SNAPSHOT_CHAT_LOG);
                rows.add(Row.CUSTOM_CONTENT);
                rows.add(Row.SNAPSHOT_MAX_RES);
            }
            case EDITOR -> {
                rows.add(Row.SCALE_ALL);
                rows.add(Row.SCALE_WORLDSPACE);
                rows.add(Row.SCALE_HUD);
                rows.add(Row.MENU_SPACE_COMMAND);
                rows.add(Row.MENU_SPACE_TEMPLATE_BLOCKS);
                rows.add(Row.MENU_SPACE_CONTAINER_CONTENTS);
                rows.add(Row.MENU_SPACE_BLOCK_VARIANT);
            }
        }
        return List.copyOf(rows);
    }
}
