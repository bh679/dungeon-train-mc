package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the tab split {@link ClientOptionsTab} hands to {@link DungeonTrainClientOptionsScreen}.
 *
 * <p>Two of the sixteen rows are conditional — Political Filter on Chinese clients, Help Translate…
 * when a translation target resolves — and the screen packs rows two-across, so either one appearing
 * re-pairs the rows after it in its tab. These tests cover all four combinations, because the failure
 * mode is silent: a row quietly dropped from the model renders as a perfectly normal-looking tab that
 * is simply missing a setting.</p>
 */
final class ClientOptionsTabTest {

    private static final boolean[] BOOLS = {false, true};

    private static List<ClientOptionsTab.Row> allRows(boolean chinese, boolean translate) {
        List<ClientOptionsTab.Row> rows = new ArrayList<>();
        for (ClientOptionsTab tab : ClientOptionsTab.values()) {
            rows.addAll(ClientOptionsTab.rowsFor(tab, chinese, translate));
        }
        return rows;
    }

    // ---- Tab integrity across every visibility combination ----

    @Test
    @DisplayName("No tab is ever empty — no tab can open onto nothing")
    void noTabIsEmpty() {
        for (boolean chinese : BOOLS) {
            for (boolean translate : BOOLS) {
                for (ClientOptionsTab tab : ClientOptionsTab.values()) {
                    assertFalse(ClientOptionsTab.rowsFor(tab, chinese, translate).isEmpty(),
                            tab + " empty at chinese=" + chinese + " translate=" + translate);
                }
            }
        }
    }

    @Test
    @DisplayName("No row appears in two tabs, or twice in one")
    void noDuplicateRows() {
        for (boolean chinese : BOOLS) {
            for (boolean translate : BOOLS) {
                List<ClientOptionsTab.Row> rows = allRows(chinese, translate);
                assertEquals(rows.size(), Set.copyOf(rows).size(),
                        "duplicate at chinese=" + chinese + " translate=" + translate);
            }
        }
    }

    @Test
    @DisplayName("Tab order is General, Train, Editor")
    void tabOrder() {
        assertEquals(List.of(ClientOptionsTab.GENERAL, ClientOptionsTab.TRAIN, ClientOptionsTab.EDITOR),
                List.of(ClientOptionsTab.values()));
    }

    // ---- The conditional rows ----

    @Test
    @DisplayName("Plain client: seventeen rows, neither conditional row present")
    void plainClient() {
        List<ClientOptionsTab.Row> rows = allRows(false, false);

        assertEquals(17, rows.size());
        assertFalse(rows.contains(ClientOptionsTab.Row.POLITICAL_FILTER));
        assertFalse(rows.contains(ClientOptionsTab.Row.TRANSLATE));
    }

    @Test
    @DisplayName("Chinese locale adds Political Filter to General, right after Content")
    void chineseLocale_addsPoliticalFilter() {
        List<ClientOptionsTab.Row> general =
                ClientOptionsTab.rowsFor(ClientOptionsTab.GENERAL, true, false);

        assertEquals(List.of(ClientOptionsTab.Row.CONTENT_MODE,
                        ClientOptionsTab.Row.POLITICAL_FILTER,
                        ClientOptionsTab.Row.BOOK_AUTHOR_CHAT,
                        ClientOptionsTab.Row.CINEMATIC_HOTKEY,
                        ClientOptionsTab.Row.BACKUPS,
                        ClientOptionsTab.Row.BACKUPS_PER_VERSION,
                        ClientOptionsTab.Row.CLEAR_BACKUPS),
                general);
        assertFalse(general.contains(ClientOptionsTab.Row.TRANSLATE));
    }

    @Test
    @DisplayName("A translation target adds Help Translate… to the end of General")
    void translateTarget_addsTranslateRow() {
        List<ClientOptionsTab.Row> general =
                ClientOptionsTab.rowsFor(ClientOptionsTab.GENERAL, false, true);

        assertEquals(ClientOptionsTab.Row.TRANSLATE, general.get(general.size() - 1));
        assertFalse(general.contains(ClientOptionsTab.Row.POLITICAL_FILTER));
    }

    @Test
    @DisplayName("Both conditions together surface every row the screen knows about")
    void bothConditions_surfaceEveryRow() {
        List<ClientOptionsTab.Row> rows = allRows(true, true);

        assertEquals(19, rows.size());
        assertEquals(EnumSet.allOf(ClientOptionsTab.Row.class), EnumSet.copyOf(rows),
                "every Row constant must appear in some tab when both conditions hold");
    }

    @Test
    @DisplayName("The backup rows lead a group so they are not split across a pair boundary")
    void backupRowsStartTheirOwnGroup() {
        // Rows pack two-across in list order. Without a group leader, BACKUPS pairs with whatever
        // row precedes it and the three backup rows stop reading as one block.
        assertTrue(ClientOptionsTab.startsGroup(ClientOptionsTab.Row.BACKUPS));
        // The rest of the group pairs among themselves, so they must NOT be leaders.
        assertFalse(ClientOptionsTab.startsGroup(ClientOptionsTab.Row.BACKUPS_PER_VERSION));
        assertFalse(ClientOptionsTab.startsGroup(ClientOptionsTab.Row.CLEAR_BACKUPS));
        // Translate follows the group and must not be dragged into it.
        assertTrue(ClientOptionsTab.startsGroup(ClientOptionsTab.Row.TRANSLATE));
    }

    @Test
    @DisplayName("The three backup rows stay adjacent, in order")
    void backupRowsAreAdjacent() {
        List<ClientOptionsTab.Row> general =
                ClientOptionsTab.rowsFor(ClientOptionsTab.GENERAL, false, false);
        int first = general.indexOf(ClientOptionsTab.Row.BACKUPS);

        assertEquals(ClientOptionsTab.Row.BACKUPS_PER_VERSION, general.get(first + 1));
        assertEquals(ClientOptionsTab.Row.CLEAR_BACKUPS, general.get(first + 2));
    }

    // ---- Fixed tabs are unaffected by the conditional flags ----

    @Test
    @DisplayName("Train and Editor hold the same rows whatever the flags")
    void fixedTabs_areUnconditional() {
        // Volume and Chat Log lead, adjacent, so the width-aware packing pairs them on one line.
        List<ClientOptionsTab.Row> train = List.of(ClientOptionsTab.Row.TRAIN_VOLUME,
                ClientOptionsTab.Row.SNAPSHOT_CHAT_LOG,
                ClientOptionsTab.Row.CUSTOM_CONTENT,
                ClientOptionsTab.Row.SNAPSHOT_MAX_RES);
        List<ClientOptionsTab.Row> editor = List.of(ClientOptionsTab.Row.SCALE_ALL,
                ClientOptionsTab.Row.SCALE_WORLDSPACE,
                ClientOptionsTab.Row.SCALE_HUD,
                ClientOptionsTab.Row.MENU_SPACE_COMMAND,
                ClientOptionsTab.Row.MENU_SPACE_TEMPLATE_BLOCKS,
                ClientOptionsTab.Row.MENU_SPACE_CONTAINER_CONTENTS,
                ClientOptionsTab.Row.MENU_SPACE_BLOCK_VARIANT);

        for (boolean chinese : BOOLS) {
            for (boolean translate : BOOLS) {
                assertEquals(train, ClientOptionsTab.rowsFor(ClientOptionsTab.TRAIN, chinese, translate));
                assertEquals(editor, ClientOptionsTab.rowsFor(ClientOptionsTab.EDITOR, chinese, translate));
            }
        }
    }

    // ---- Title keys, selection memory, immutability ----

    @Test
    @DisplayName("Title keys share the tab prefix")
    void titleKeys_useSharedPrefix() {
        for (ClientOptionsTab tab : ClientOptionsTab.values()) {
            assertTrue(tab.titleKey().startsWith("gui.dungeontrain.options.tab."), tab.titleKey());
        }
        assertEquals("gui.dungeontrain.options.tab.train", ClientOptionsTab.TRAIN.titleKey());
    }

    @Test
    @DisplayName("The chosen tab is remembered, and a null selection is ignored")
    void selection_isRememberedAndNullSafe() {
        ClientOptionsTab original = ClientOptionsTab.active();
        try {
            ClientOptionsTab.select(ClientOptionsTab.EDITOR);
            assertEquals(ClientOptionsTab.EDITOR, ClientOptionsTab.active());

            ClientOptionsTab.select(null);
            assertEquals(ClientOptionsTab.EDITOR, ClientOptionsTab.active(), "null must not clear it");
        } finally {
            ClientOptionsTab.select(original);
        }
    }

    @Test
    @DisplayName("A tab's row list is defensively copied, not a live reference")
    void rows_areImmutable() {
        List<ClientOptionsTab.Row> rows = ClientOptionsTab.rowsFor(ClientOptionsTab.TRAIN, true, true);
        assertThrows(UnsupportedOperationException.class,
                () -> rows.add(ClientOptionsTab.Row.SCALE_HUD));
    }
}
