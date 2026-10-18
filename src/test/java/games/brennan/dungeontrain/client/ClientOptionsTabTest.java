package games.brennan.dungeontrain.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the tab split {@link ClientOptionsTab} hands to {@link DungeonTrainClientOptionsScreen}.
 *
 * <p>Three of the twenty-four rows are conditional — Political Filter on Chinese clients, Help Translate…
 * when a translation target resolves, Catch-up spawning everywhere but a multiplayer client — and the
 * screen packs rows two-across, so any one appearing
 * re-pairs the rows after it in its tab. These tests cover every combination, because the failure
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
    @DisplayName("No row appears twice in one tab; only the Live Feed's frame-rate costs appear in two tabs")
    void noDuplicateRows() {
        // Stream is their home; Performance repeats them for players who came there to stop lag.
        Set<ClientOptionsTab.Row> sharedWithPerformance = Set.of(ClientOptionsTab.Row.LIVE_FEED_VIEWING,
                ClientOptionsTab.Row.LIVESTREAM, ClientOptionsTab.Row.STREAM_SIZE, ClientOptionsTab.Row.STREAM_SAVE_BANDWIDTH,
                ClientOptionsTab.Row.STREAM_POPUPS_OVER_TAG);
        for (boolean chinese : BOOLS) {
            for (boolean translate : BOOLS) {
                List<ClientOptionsTab.Row> seen = new ArrayList<>();
                Set<ClientOptionsTab.Row> repeated = new java.util.HashSet<>();
                for (ClientOptionsTab tab : ClientOptionsTab.values()) {
                    List<ClientOptionsTab.Row> rows = ClientOptionsTab.rowsFor(tab, chinese, translate, true);
                    assertEquals(rows.size(), Set.copyOf(rows).size(),
                            "duplicate in " + tab + " at chinese=" + chinese + " translate=" + translate);
                    for (ClientOptionsTab.Row row : rows) {
                        if (seen.contains(row)) repeated.add(row);
                        seen.add(row);
                    }
                }
                assertEquals(sharedWithPerformance, repeated,
                        "rows in two tabs at chinese=" + chinese + " translate=" + translate);
            }
        }
    }

    @Test
    @DisplayName("Tab order is General, Account, Backups, Train, Stream, Editor, Performance")
    void tabOrder() {
        assertEquals(List.of(ClientOptionsTab.GENERAL, ClientOptionsTab.ACCOUNT, ClientOptionsTab.BACKUPS,
                        ClientOptionsTab.TRAIN, ClientOptionsTab.STREAM, ClientOptionsTab.EDITOR, ClientOptionsTab.PERFORMANCE),
                List.of(ClientOptionsTab.values()));
    }

    // ---- The conditional rows ----

    @Test
    @DisplayName("Plain client: fifty-five rows (five repeated on Performance), none of the conditional rows present")
    void plainClient() {
        List<ClientOptionsTab.Row> rows = allRows(false, false, false);

        assertEquals(55, rows.size());
        assertFalse(rows.contains(ClientOptionsTab.Row.POLITICAL_FILTER));
        assertFalse(rows.contains(ClientOptionsTab.Row.TRANSLATE));
        assertFalse(rows.contains(ClientOptionsTab.Row.CATCH_UP_BURST));
        // Unconditional: the page must be reachable even on the barest client.
        assertTrue(rows.contains(ClientOptionsTab.Row.AI_POLICY));
        // The Discord account block is there for everyone — it is where an unlinked player links.
        assertTrue(rows.contains(ClientOptionsTab.Row.DISCORD_ACCOUNT));
        // So is the Twitch block (the screen hides it in Kid mode at draw time, not here).
        assertTrue(rows.contains(ClientOptionsTab.Row.TWITCH_ACCOUNT));
    }

    @Test
    @DisplayName("Chinese locale adds Political Filter to General, right after Content")
    void chineseLocale_addsPoliticalFilter() {
        List<ClientOptionsTab.Row> general =
                ClientOptionsTab.rowsFor(ClientOptionsTab.GENERAL, true, false, true);

        assertEquals(List.of(ClientOptionsTab.Row.DIFFICULTY,
                        ClientOptionsTab.Row.CONTENT_MODE,
                        ClientOptionsTab.Row.POLITICAL_FILTER,
                        ClientOptionsTab.Row.DEV_CHAT,
                        ClientOptionsTab.Row.BOOK_AUTHOR_CHAT,
                        ClientOptionsTab.Row.UPDATE_NOTICE_CHAT,
                        ClientOptionsTab.Row.OTHER_ADVANCEMENT_TABS,
                        ClientOptionsTab.Row.PROTECT_OWN_PETS,
                        ClientOptionsTab.Row.CINEMATIC_HOTKEY,
                        ClientOptionsTab.Row.BACKPACK_BUTTON,
                        ClientOptionsTab.Row.BACKPACK_AUTO_COLLECT,
                        ClientOptionsTab.Row.BOOK_FILE_BUTTONS,
                        ClientOptionsTab.Row.BOOK_INSERT_PAGE_BUTTON,
                        ClientOptionsTab.Row.AI_POLICY,
                        ClientOptionsTab.Row.BOOK_LANGUAGES),
                general);
        assertFalse(general.contains(ClientOptionsTab.Row.TRANSLATE));
    }

    @Test
    @DisplayName("Dev HUD is on General, after the cinematic hotkey, on dev builds only")
    void devHud_onlyOnDevBuilds() {
        for (boolean chinese : BOOLS) {
            for (boolean translate : BOOLS) {
                for (ClientOptionsTab tab : ClientOptionsTab.values()) {
                    assertFalse(ClientOptionsTab.rowsFor(tab, chinese, translate, true, false)
                            .contains(ClientOptionsTab.Row.DEV_HUD), tab + ": Dev HUD on a release build");
                }
                List<ClientOptionsTab.Row> general =
                        ClientOptionsTab.rowsFor(ClientOptionsTab.GENERAL, chinese, translate, true, true);
                assertEquals(general.indexOf(ClientOptionsTab.Row.CINEMATIC_HOTKEY) + 1,
                        general.indexOf(ClientOptionsTab.Row.DEV_HUD));
            }
        }
    }

    @Test
    @DisplayName("The low-memory notice leads the Performance tab, then the Live Feed's costs, above the tips")
    void lowMemoryNoticeLeadsPerformance() {
        assertEquals(List.of(ClientOptionsTab.Row.LOW_MEMORY_NOTICE_CHAT,
                        ClientOptionsTab.Row.LIVE_FEED_VIEWING, ClientOptionsTab.Row.LIVESTREAM,
                        ClientOptionsTab.Row.STREAM_SIZE, ClientOptionsTab.Row.STREAM_SAVE_BANDWIDTH,
                        ClientOptionsTab.Row.STREAM_POPUPS_OVER_TAG, ClientOptionsTab.Row.PERFORMANCE_TIPS),
                ClientOptionsTab.rowsFor(ClientOptionsTab.PERFORMANCE, false, false, true));
        assertFalse(ClientOptionsTab.rowsFor(ClientOptionsTab.GENERAL, true, true, true)
                .contains(ClientOptionsTab.Row.LOW_MEMORY_NOTICE_CHAT));
    }

    @Test
    @DisplayName("The backup rows live on their own tab, not on General")
    void backupRowsLiveOnTheirOwnTab() {
        for (boolean chinese : BOOLS) {
            for (boolean translate : BOOLS) {
                List<ClientOptionsTab.Row> general =
                        ClientOptionsTab.rowsFor(ClientOptionsTab.GENERAL, chinese, translate, true);
                assertFalse(general.contains(ClientOptionsTab.Row.BACKUPS));
                assertFalse(general.contains(ClientOptionsTab.Row.CONFIRM_BUILD_RESTORE));
            }
        }
    }

    @Test
    @DisplayName("A translation target adds Help Translate… after the ungrouped settings")
    void translateTarget_addsTranslateRow() {
        List<ClientOptionsTab.Row> general =
                ClientOptionsTab.rowsFor(ClientOptionsTab.GENERAL, false, true, true);

        // Translate sits just before Book Languages, which closes the tab so it never splits the pair.
        assertEquals(ClientOptionsTab.Row.TRANSLATE, general.get(general.size() - 2));
        assertEquals(ClientOptionsTab.Row.BOOK_LANGUAGES, general.get(general.size() - 1));
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
    @DisplayName("All conditions together, on a dev build, surface every row the screen knows about")
    void allConditions_surfaceEveryRow() {
        List<ClientOptionsTab.Row> rows = new ArrayList<>();
        for (ClientOptionsTab tab : ClientOptionsTab.values()) {
            rows.addAll(ClientOptionsTab.rowsFor(tab, true, true, true, true));
        }

        // 54 rows, five of them (the Live Feed's frame-rate costs) listed on Performance as well as Stream.
        assertEquals(59, rows.size());
        assertEquals(EnumSet.allOf(ClientOptionsTab.Row.class), EnumSet.copyOf(rows),
                "every Row constant must appear in some tab when all conditions hold");
    }

    @Test
    @DisplayName("The Stream tab holds Livestreaming and the what-viewers-see rows; General no longer does")
    void streamTabRows() {
        assertEquals(List.of(ClientOptionsTab.Row.LIVESTREAM, ClientOptionsTab.Row.STREAM_GUI,
                        ClientOptionsTab.Row.STREAM_CHAT, ClientOptionsTab.Row.STREAM_AUDIO, ClientOptionsTab.Row.STREAM_NAME_TAG,
                        ClientOptionsTab.Row.STREAM_POPUPS_OVER_TAG, ClientOptionsTab.Row.LIVE_CHAT,
                        ClientOptionsTab.Row.LIVE_MOMENT_ADVANCEMENTS, ClientOptionsTab.Row.LIVE_MOMENT_PHOTOS,
                        ClientOptionsTab.Row.LIVE_MOMENT_BOOKS, ClientOptionsTab.Row.TWITCH_HINT, ClientOptionsTab.Row.LIVE_FEED_VIEWING, ClientOptionsTab.Row.STREAM_SIZE,
                        ClientOptionsTab.Row.STREAM_SAVE_BANDWIDTH,
                        ClientOptionsTab.Row.HEAD_VIEWER_SIZE),
                ClientOptionsTab.rowsFor(ClientOptionsTab.STREAM, false, false, true));
        assertFalse(ClientOptionsTab.rowsFor(ClientOptionsTab.GENERAL, false, false, true)
                .contains(ClientOptionsTab.Row.LIVESTREAM));
    }

    @Test
    @DisplayName("The backup rows pair among themselves — none is a group leader")
    void backupRowsStartTheirOwnGroup() {
        // Rows pack two-across in list order. On their own tab the block needs no leader and no
        // heading; a leader here would break the two short rows off their shared line.
        assertFalse(ClientOptionsTab.startsGroup(ClientOptionsTab.Row.BACKUPS));
        assertFalse(ClientOptionsTab.startsGroup(ClientOptionsTab.Row.BACKUPS_PER_VERSION));
        assertFalse(ClientOptionsTab.startsGroup(ClientOptionsTab.Row.CLEAR_BACKUPS));
        assertFalse(ClientOptionsTab.startsGroup(ClientOptionsTab.Row.CONFIRM_BUILD_RESTORE));
        // AI Policy leads the page-opening pair at the foot of General. It, not Translate, is
        // the leader: Translate is conditional, so leading with it would break the pair apart on
        // every client where it is absent.
        assertTrue(ClientOptionsTab.startsGroup(ClientOptionsTab.Row.AI_POLICY));
        assertFalse(ClientOptionsTab.startsGroup(ClientOptionsTab.Row.TRANSLATE));
    }

    @Test
    @DisplayName("The Backups tab holds the four backup rows, in order, whatever the flags")
    void backupRowsAreAdjacent() {
        for (boolean chinese : BOOLS) {
            for (boolean translate : BOOLS) {
                List<ClientOptionsTab.Row> backups =
                        ClientOptionsTab.rowsFor(ClientOptionsTab.BACKUPS, chinese, translate, true);
                // Restores read builds out of these same archives, so the confirm question belongs here.
                assertEquals(List.of(ClientOptionsTab.Row.BACKUPS,
                                ClientOptionsTab.Row.BACKUPS_PER_VERSION,
                                ClientOptionsTab.Row.CLEAR_BACKUPS,
                                ClientOptionsTab.Row.CONFIRM_BUILD_RESTORE),
                        backups);
            }
        }
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
                ClientOptionsTab.Row.MENU_SPACE_BLOCK_VARIANT,
                ClientOptionsTab.Row.CREATIVE_MOD_BLOCK_TABS,
                ClientOptionsTab.Row.CREATIVE_MOD_BLOCKS_IN_SEARCH);

        for (boolean chinese : BOOLS) {
            for (boolean translate : BOOLS) {
                assertEquals(train, ClientOptionsTab.rowsFor(ClientOptionsTab.TRAIN, chinese, translate, true));
                assertEquals(editor, ClientOptionsTab.rowsFor(ClientOptionsTab.EDITOR, chinese, translate, true));
            }
        }
    }

    @Test
    @DisplayName("Catch-up spawning is absent on a multiplayer client, where the value is the server's")
    void catchUpBurst_absentWhenNotWritable() {
        for (boolean chinese : BOOLS) {
            for (boolean translate : BOOLS) {
                List<ClientOptionsTab.Row> train =
                        ClientOptionsTab.rowsFor(ClientOptionsTab.TRAIN, chinese, translate, false);
                assertFalse(train.contains(ClientOptionsTab.Row.CATCH_UP_BURST),
                        "a control that cannot write the value the player would see must not be "
                                + "shown at all");
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

    // ---- Every row names itself, and has the one-line hint the settings list draws under the name ----

    @Test
    @DisplayName("every setting row has a caption key and a .hint in en_us; the expanded rows have neither")
    void everyRowHasCaptionAndHint() throws IOException {
        JsonObject en = JsonParser.parseString(Files.readString(RepoPaths.langFile("en_us"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        for (ClientOptionsTab.Row row : ClientOptionsTab.Row.values()) {
            if (row == ClientOptionsTab.Row.DISCORD_ACCOUNT || row == ClientOptionsTab.Row.TWITCH_ACCOUNT || row == ClientOptionsTab.Row.PERFORMANCE_TIPS) {
                assertNull(row.captionKey(), row + " stands for several rows and has no caption of its own");
                continue;
            }
            String key = row.captionKey();
            assertNotNull(key, row + " has no caption key");
            assertTrue(en.has(key), row + ": en_us lacks its name " + key);
            assertTrue(en.has(key + ".hint"), row + ": en_us lacks its hint " + key + ".hint");
        }
    }

    @Test
    @DisplayName("hints are one short line — the list cuts anything longer with an ellipsis")
    void hintsAreShort() throws IOException {
        JsonObject en = JsonParser.parseString(Files.readString(RepoPaths.langFile("en_us"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        for (ClientOptionsTab.Row row : ClientOptionsTab.Row.values()) {
            if (row.captionKey() == null) continue;
            String hint = en.get(row.captionKey() + ".hint").getAsString();
            // ~180px of 5-6px glyphs at GUI scale 1 beside a 100px control; longer reads as cut.
            assertTrue(hint.length() <= 44, row + ": hint too long to fit the row: \"" + hint + "\"");
            assertFalse(hint.endsWith("."), row + ": a hint is a fragment, not a sentence: \"" + hint + "\"");
        }
    }
}
