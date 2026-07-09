package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.editor.CarriageContentsGroupStore;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsGroup;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the sub-variant drilldown behaviour: {@link ContentsSubVariantScreen}
 * lists a group parent's members, and {@link CategoryTemplatesScreen} hides
 * those members at the top level while turning the parent into a drill-in.
 *
 * <p>Regression guard for the bug where group members (e.g. {@code copper},
 * {@code stone}, {@code desert}) rendered as flat top-level rows in the
 * Contents template picker despite belonging to the {@code maze} group in the
 * data — the picker never consulted group membership.</p>
 */
final class ContentsSubVariantScreenTest {

    private static CarriageContentsGroup mazeGroup() {
        return new CarriageContentsGroup(1, List.of(
            new CarriageContentsGroup.Member("copper", 10),
            new CarriageContentsGroup.Member("stone", 10),
            new CarriageContentsGroup.Member("desert", 10)));
    }

    @BeforeEach
    @AfterEach
    void cleanSlate() {
        CarriageContentsRegistry.clear();
        CarriageContentsGroupStore.clearCache();
    }

    @Test
    @DisplayName("entries: parent-self row first, one Run per member, Back last")
    void entries_selfPlusMembersPlusBack() {
        CarriageContentsGroupStore.injectForTesting("maze", mazeGroup());
        ContentsSubVariantScreen screen = new ContentsSubVariantScreen("maze");
        List<CommandMenuEntry> entries = screen.entries();

        // self + 3 members + Back
        assertEquals(5, entries.size());

        CommandMenuEntry.Run self = (CommandMenuEntry.Run) entries.get(0);
        assertEquals("maze (self)", self.label());
        assertEquals("dungeontrain editor contents enter maze", self.command());

        CommandMenuEntry.Run copper = (CommandMenuEntry.Run) entries.get(1);
        assertEquals("copper", copper.label());
        assertEquals("dungeontrain editor contents enter copper", copper.command());

        assertInstanceOf(CommandMenuEntry.Back.class, entries.get(4));
    }

    @Test
    @DisplayName("Missing group degrades to self row + Back (defensive)")
    void entries_missingGroup_selfAndBackOnly() {
        ContentsSubVariantScreen screen = new ContentsSubVariantScreen("ghost");
        List<CommandMenuEntry> entries = screen.entries();
        assertEquals(2, entries.size());
        assertEquals("ghost (self)", ((CommandMenuEntry.Run) entries.get(0)).label());
        assertInstanceOf(CommandMenuEntry.Back.class, entries.get(1));
    }

    @Test
    @DisplayName("title is the parent id for the breadcrumb band")
    void title_isParentId() {
        assertEquals("maze", new ContentsSubVariantScreen("maze").title());
    }

    @Test
    @DisplayName("CategoryTemplatesScreen: members hidden at top level, parent becomes a DrillIn")
    void categoryScreen_hidesMembers_parentDrillsIn() {
        // Register the parent + its members as pickable contents so they'd
        // otherwise all show at the top level, then group the members.
        CarriageContentsRegistry.register((CarriageContents.Custom) CarriageContents.custom("maze"));
        CarriageContentsRegistry.register((CarriageContents.Custom) CarriageContents.custom("copper"));
        CarriageContentsRegistry.register((CarriageContents.Custom) CarriageContents.custom("stone"));
        CarriageContentsRegistry.register((CarriageContents.Custom) CarriageContents.custom("desert"));
        CarriageContentsGroupStore.injectForTesting("maze", mazeGroup());

        List<CommandMenuEntry> entries = new CategoryTemplatesScreen("contents").entries();

        boolean mazeIsDrillIn = false;
        for (CommandMenuEntry e : entries) {
            if (e instanceof CommandMenuEntry.DrillIn d && "maze".equals(d.label())) {
                mazeIsDrillIn = true;
                assertInstanceOf(ContentsSubVariantScreen.class, d.target());
            }
            // No member should appear as a top-level row.
            if (e instanceof CommandMenuEntry.Run r) {
                assertFalse("copper".equals(r.label()), "copper must not be top-level");
                assertFalse("stone".equals(r.label()), "stone must not be top-level");
                assertFalse("desert".equals(r.label()), "desert must not be top-level");
            }
            if (e instanceof CommandMenuEntry.DrillIn d) {
                assertFalse("copper".equals(d.label()), "copper must not be top-level");
            }
        }
        assertTrue(mazeIsDrillIn, "maze parent should be a DrillIn into its sub-variants");
    }
}
