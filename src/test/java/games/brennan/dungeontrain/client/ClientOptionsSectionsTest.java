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
 * Pins the grouping {@link ClientOptionsSections} hands to {@link DungeonTrainClientOptionsScreen}.
 *
 * <p>Two of the eleven rows are conditional — Political Filter on Chinese clients, Translate… when a
 * translation target resolves — and the screen packs rows two-across, so either one appearing
 * re-pairs the rows after it. These tests cover all four combinations, because the failure mode is
 * silent: a stranded section header over an empty group, or a row quietly dropped from the model,
 * renders as a perfectly normal-looking screen that is simply missing a setting.</p>
 */
final class ClientOptionsSectionsTest {

    private static final List<String> EXPECTED_ORDER =
            List.of("display_audio", "content", "snapshots", "chat_hotkeys", "network");

    private static List<ClientOptionsSections.Row> allRows(List<ClientOptionsSections.Section> sections) {
        List<ClientOptionsSections.Row> rows = new ArrayList<>();
        for (ClientOptionsSections.Section s : sections) {
            rows.addAll(s.rows());
        }
        return rows;
    }

    // ---- Section order and integrity, in every visibility combination ----

    @Test
    @DisplayName("Section order is stable across all four visibility combinations")
    void sectionOrder_isStable() {
        for (boolean chinese : new boolean[]{false, true}) {
            for (boolean translate : new boolean[]{false, true}) {
                List<String> actual = ClientOptionsSections.visibleSections(chinese, translate)
                        .stream().map(ClientOptionsSections.Section::titleKey).toList();
                assertEquals(EXPECTED_ORDER, actual,
                        "chinese=" + chinese + " translate=" + translate);
            }
        }
    }

    @Test
    @DisplayName("No section is ever empty — a header never strands over nothing")
    void noSectionIsEmpty() {
        for (boolean chinese : new boolean[]{false, true}) {
            for (boolean translate : new boolean[]{false, true}) {
                for (ClientOptionsSections.Section s : ClientOptionsSections.visibleSections(chinese, translate)) {
                    assertFalse(s.rows().isEmpty(),
                            s.titleKey() + " empty at chinese=" + chinese + " translate=" + translate);
                }
            }
        }
    }

    @Test
    @DisplayName("No row is ever listed twice")
    void noDuplicateRows() {
        for (boolean chinese : new boolean[]{false, true}) {
            for (boolean translate : new boolean[]{false, true}) {
                List<ClientOptionsSections.Row> rows =
                        allRows(ClientOptionsSections.visibleSections(chinese, translate));
                assertEquals(rows.size(), Set.copyOf(rows).size(),
                        "duplicate at chinese=" + chinese + " translate=" + translate);
            }
        }
    }

    // ---- The conditional rows ----

    @Test
    @DisplayName("Plain client: nine rows, neither conditional row present")
    void plainClient_hasNineRows() {
        List<ClientOptionsSections.Row> rows =
                allRows(ClientOptionsSections.visibleSections(false, false));

        assertEquals(9, rows.size());
        assertFalse(rows.contains(ClientOptionsSections.Row.POLITICAL_FILTER));
        assertFalse(rows.contains(ClientOptionsSections.Row.TRANSLATE));
    }

    @Test
    @DisplayName("Chinese locale adds Political Filter, and only that, to the Content section")
    void chineseLocale_addsPoliticalFilter() {
        List<ClientOptionsSections.Section> sections = ClientOptionsSections.visibleSections(true, false);
        List<ClientOptionsSections.Row> rows = allRows(sections);

        assertEquals(10, rows.size());
        assertTrue(rows.contains(ClientOptionsSections.Row.POLITICAL_FILTER));
        assertFalse(rows.contains(ClientOptionsSections.Row.TRANSLATE));

        ClientOptionsSections.Section content = sections.stream()
                .filter(s -> s.titleKey().equals("content")).findFirst().orElseThrow();
        assertEquals(List.of(ClientOptionsSections.Row.CONTENT_MODE,
                        ClientOptionsSections.Row.CUSTOM_CONTENT,
                        ClientOptionsSections.Row.POLITICAL_FILTER),
                content.rows());
    }

    @Test
    @DisplayName("A translation target adds Translate…, and only that, to the Network section")
    void translateTarget_addsTranslateRow() {
        List<ClientOptionsSections.Section> sections = ClientOptionsSections.visibleSections(false, true);
        List<ClientOptionsSections.Row> rows = allRows(sections);

        assertEquals(10, rows.size());
        assertTrue(rows.contains(ClientOptionsSections.Row.TRANSLATE));
        assertFalse(rows.contains(ClientOptionsSections.Row.POLITICAL_FILTER));

        ClientOptionsSections.Section network = sections.stream()
                .filter(s -> s.titleKey().equals("network")).findFirst().orElseThrow();
        assertEquals(List.of(ClientOptionsSections.Row.INTERNET,
                        ClientOptionsSections.Row.TRANSLATE),
                network.rows());
    }

    @Test
    @DisplayName("Both conditions together surface every row the screen knows about")
    void bothConditions_surfaceEveryRow() {
        List<ClientOptionsSections.Row> rows =
                allRows(ClientOptionsSections.visibleSections(true, true));

        assertEquals(11, rows.size());
        assertEquals(EnumSet.allOf(ClientOptionsSections.Row.class), EnumSet.copyOf(rows),
                "every Row constant must appear somewhere when both conditions hold");
    }

    // ---- Header keys and immutability ----

    @Test
    @DisplayName("Header keys are the shared prefix plus the section's own suffix")
    void headerKeys_useSharedPrefix() {
        for (ClientOptionsSections.Section s : ClientOptionsSections.visibleSections(true, true)) {
            assertEquals(ClientOptionsSections.SECTION_KEY_PREFIX + s.titleKey(), s.fullTitleKey());
            assertTrue(s.fullTitleKey().startsWith("gui.dungeontrain.options.section."));
        }
    }

    @Test
    @DisplayName("A section's row list is defensively copied, not a live reference")
    void sectionRows_areImmutable() {
        ClientOptionsSections.Section section =
                ClientOptionsSections.visibleSections(true, true).get(0);
        assertThrows(UnsupportedOperationException.class,
                () -> section.rows().add(ClientOptionsSections.Row.INTERNET));
    }
}
