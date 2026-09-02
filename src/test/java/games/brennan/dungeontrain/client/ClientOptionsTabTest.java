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
 * <p>Two of the twenty-one rows are conditional — Political Filter on Chinese clients, Help Translate…
 * when a translation target resolves — and the screen packs rows two-across, so either one appearing
 * re-pairs the rows after it in its tab. These tests cover all four combinations, because the failure
 * mode is silent: a row quietly dropped from the model renders as a perfectly normal-looking tab that
 * is simply missing a setting.</p>
 */
final class ClientOptionsTabTest {

    private static final boolean[] BOOLS = {false, true};

    private static List<ClientOptionsTab.Row> allRows(boolean chinese, boolean translate, boolean writable) {
        List<ClientOptionsTab.Row> rows = new ArrayList<>();
        for (ClientOptionsTab tab : ClientOptionsTab.values()) {
            rows.addAll(ClientOptionsTab.rowsFor(tab, chinese, translate, writable));
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
                    assertFalse(ClientOptionsTab.rowsFor(tab, chinese, translate, true).isEmpty(),
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
                List<ClientOptionsTab.Row> rows = allRows(chinese, translate, true);
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
    @DisplayName("Plain client: twenty-one rows, none of the conditional rows present")
    void plainClient() {
        List<ClientOptionsTab.Row> rows = allRows(false, false, false);

        assertEquals(21, rows.size());
        assertFalse(rows.contains(ClientOptionsTab.Row.POLITICAL_FILTER));
        assertFalse(rows.contains(ClientOptionsTab.Row.TRANSLATE));
        assertFalse(rows.contains(ClientOptionsTab.Row.CATCH_UP_BURST));
        // Unconditional: the page must be reachable even on the barest client.
        assertTrue(rows.contains(ClientOptionsTab.Row.AI_POLICY));
    }

    @Test
    @DisplayName("Chinese locale adds Political Filter to General, right after Content")
    void chineseLocale_addsPoliticalFilter() {
        List<ClientOptionsTab.Row> general =
                ClientOptionsTab.rowsFor(ClientOptionsTab.GENERAL, true, false, true);

        assertEquals(List.of(ClientOptionsTab.Row.CONTENT_MODE,
                        ClientOptionsTab.Row.POLITICAL_FILTER,
                        ClientOptionsTab.Row.BOOK_AUTHOR_CHAT,
                        ClientOptionsTab.Row.CINEMATIC_HOTKEY,
                        ClientOptionsTab.Row.BACKPACK_BUTTON,
                        ClientOptionsTab.Row.AI_POLICY,
                        ClientOptionsTab.Row.BACKUPS_HEADING,
                        ClientOptionsTab.Row.BACKUPS,
                        ClientOptionsTab.Row.BACKUPS_PER_VERSION,
                        ClientOptionsTab.Row.CLEAR_BACKUPS,
                        ClientOptionsTab.Row.CONFIRM_BUILD_RESTORE),
                general);
        assertFalse(general.contains(ClientOptionsTab.Row.TRANSLATE));
    }

    @Test
    @DisplayName("Help Translate sits above the backup block, not after it")
    void translateSitsAboveTheBackupBlock() {
        List<ClientOptionsTab.Row> general =
                ClientOptionsTab.rowsFor(ClientOptionsTab.GENERAL, false, true, true);

        assertTrue(general.indexOf(ClientOptionsTab.Row.TRANSLATE)
                < general.indexOf(ClientOptionsTab.Row.BACKUPS_HEADING));
    }

    @Test
    @DisplayName("A translation target adds Help Translate… after the ungrouped settings")
    void translateTarget_addsTranslateRow() {
        List<ClientOptionsTab.Row> general =
                ClientOptionsTab.rowsFor(ClientOptionsTab.GENERAL, false, true, true);

        // It used to close the tab; the backup block now does, so Translate is the last of the
        // ungrouped rows rather than the last row outright.
        assertEquals(ClientOptionsTab.Row.TRANSLATE,
                general.get(general.indexOf(ClientOptionsTab.Row.BACKUPS_HEADING) - 1));
        assertFalse(general.contains(ClientOptionsTab.Row.POLITICAL_FILTER));
    }

    @Test
    @DisplayName("AI Policy is present in every combination, always ahead of Help Translate")
    void aiPolicyIsUnconditional() {
        for (boolean chinese : BOOLS) {
            for (boolean translate : BOOLS) {
                List<ClientOptionsTab.Row> general =
                        ClientOptionsTab.rowsFor(ClientOptionsTab.GENERAL, chinese, translate, true);
                int policy = general.indexOf(ClientOptionsTab.Row.AI_POLICY);
                assertTrue(policy >= 0,
                        "AI Policy missing at chinese=" + chinese + " translate=" + translate);
                if (translate) {
                    // It leads the pair, so it must come first for them to pack onto one line.
                    assertEquals(policy + 1, general.indexOf(ClientOptionsTab.Row.TRANSLATE));
                }
            }
        }
    }

    @Test
    @DisplayName("All three conditions together surface every row the screen knows about")
    void allConditions_surfaceEveryRow() {
        List<ClientOptionsTab.Row> rows = allRows(true, true, true);

        assertEquals(24, rows.size());
        assertEquals(EnumSet.allOf(ClientOptionsTab.Row.class), EnumSet.copyOf(rows),
                "every Row constant must appear in some tab when all conditions hold");
    }

    @Test
    @DisplayName("The backup rows lead a group so they are not split across a pair boundary")
    void backupRowsStartTheirOwnGroup() {
        // Rows pack two-across in list order. Without a group leader, BACKUPS pairs with whatever
        // row precedes it and the three backup rows stop reading as one block.
        assertTrue(ClientOptionsTab.startsGroup(ClientOptionsTab.Row.BACKUPS_HEADING));
        assertTrue(ClientOptionsTab.isHeading(ClientOptionsTab.Row.BACKUPS_HEADING));
        // The rest of the group pairs among themselves, so they must NOT be leaders.
        assertFalse(ClientOptionsTab.startsGroup(ClientOptionsTab.Row.BACKUPS));
        assertFalse(ClientOptionsTab.startsGroup(ClientOptionsTab.Row.BACKUPS_PER_VERSION));
        assertFalse(ClientOptionsTab.startsGroup(ClientOptionsTab.Row.CLEAR_BACKUPS));
        assertFalse(ClientOptionsTab.startsGroup(ClientOptionsTab.Row.CONFIRM_BUILD_RESTORE));
        // AI Policy leads the page-opening pair above the backup block. It, not Translate, is
        // the leader: Translate is conditional, so leading with it would break the pair apart on
        // every client where it is absent.
        assertTrue(ClientOptionsTab.startsGroup(ClientOptionsTab.Row.AI_POLICY));
        assertFalse(ClientOptionsTab.startsGroup(ClientOptionsTab.Row.TRANSLATE));
    }

    @Test
    @DisplayName("The backup rows stay adjacent, in order")
    void backupRowsAreAdjacent() {
        List<ClientOptionsTab.Row> general =
                ClientOptionsTab.rowsFor(ClientOptionsTab.GENERAL, false, false, true);
        int first = general.indexOf(ClientOptionsTab.Row.BACKUPS_HEADING);

        assertEquals(ClientOptionsTab.Row.BACKUPS, general.get(first + 1));
        assertEquals(ClientOptionsTab.Row.BACKUPS_PER_VERSION, general.get(first + 2));
        assertEquals(ClientOptionsTab.Row.CLEAR_BACKUPS, general.get(first + 3));
        // Restores read builds out of these same archives, so the question about them belongs here.
        assertEquals(ClientOptionsTab.Row.CONFIRM_BUILD_RESTORE, general.get(first + 4));
        assertEquals(general.size() - 1, first + 4, "the backup block ends the tab");
    }

    // ---- Fixed tabs are unaffected by the conditional flags ----

    @Test
    @DisplayName("Train and Editor hold the same rows whatever the flags")
    void fixedTabs_areUnconditional() {
        // Volume and Chat Log lead, adjacent, so the width-aware packing pairs them on one line.
        List<ClientOptionsTab.Row> train = List.of(ClientOptionsTab.Row.TRAIN_VOLUME,
                ClientOptionsTab.Row.SNAPSHOT_CHAT_LOG,
                ClientOptionsTab.Row.CUSTOM_CONTENT,
                ClientOptionsTab.Row.SNAPSHOT_MAX_RES,
                ClientOptionsTab.Row.CATCH_UP_BURST);
        List<ClientOptionsTab.Row> editor = List.of(ClientOptionsTab.Row.SCALE_ALL,
                ClientOptionsTab.Row.SCALE_WORLDSPACE,
                ClientOptionsTab.Row.SCALE_HUD,
                ClientOptionsTab.Row.MENU_SPACE_COMMAND,
                ClientOptionsTab.Row.MENU_SPACE_TEMPLATE_BLOCKS,
                ClientOptionsTab.Row.MENU_SPACE_CONTAINER_CONTENTS,
                ClientOptionsTab.Row.MENU_SPACE_BLOCK_VARIANT);

        for (boolean chinese : BOOLS) {
            for (boolean translate : BOOLS) {
                assertEquals(train, ClientOptionsTab.rowsFor(ClientOptionsTab.TRAIN, chinese, translate, true));
                assertEquals(editor, ClientOptionsTab.rowsFor(ClientOptionsTab.EDITOR, chinese, translate, true));
            }
        }
    }

    @Test
    @DisplayName("Catch-up spawning is absent when the train settings aren't writable from here")
    void catchUpBurst_absentWhenNotWritable() {
        for (boolean chinese : BOOLS) {
            for (boolean translate : BOOLS) {
                List<ClientOptionsTab.Row> train =
                        ClientOptionsTab.rowsFor(ClientOptionsTab.TRAIN, chinese, translate, false);
                assertFalse(train.contains(ClientOptionsTab.Row.CATCH_UP_BURST),
                        "a control that cannot write must not be shown at all");
                assertFalse(train.isEmpty(), "the tab must still open onto something");
            }
        }
    }

    @Test
    @DisplayName("Catch-up spawning closes the Train tab, so it can't re-pair anything")
    void catchUpBurst_isLastInTheTab() {
        for (boolean chinese : BOOLS) {
            for (boolean translate : BOOLS) {
                List<ClientOptionsTab.Row> train =
                        ClientOptionsTab.rowsFor(ClientOptionsTab.TRAIN, chinese, translate, true);
                assertEquals(ClientOptionsTab.Row.CATCH_UP_BURST, train.get(train.size() - 1),
                        "a conditional row re-pairs everything after it — so nothing may follow it");
                // The rows before it are exactly the unconditional tab.
                assertEquals(ClientOptionsTab.rowsFor(ClientOptionsTab.TRAIN, chinese, translate, false),
                        train.subList(0, train.size() - 1));
            }
        }
    }

    @Test
    @DisplayName("No tab is empty in any of the eight visibility combinations")
    void noTabIsEmpty_withWritabilityFlag() {
        for (boolean chinese : BOOLS) {
            for (boolean translate : BOOLS) {
                for (boolean writable : BOOLS) {
                    for (ClientOptionsTab tab : ClientOptionsTab.values()) {
                        assertFalse(ClientOptionsTab.rowsFor(tab, chinese, translate, writable).isEmpty(),
                                tab + " empty at chinese=" + chinese + " translate=" + translate
                                        + " writable=" + writable);
                    }
                }
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
        List<ClientOptionsTab.Row> rows = ClientOptionsTab.rowsFor(ClientOptionsTab.TRAIN, true, true, true);
        assertThrows(UnsupportedOperationException.class,
                () -> rows.add(ClientOptionsTab.Row.SCALE_HUD));
    }
}
