package games.brennan.dungeontrain.client.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sub-variant "+ New" picker.
 *
 * <p>It used to be a single row that hard-coded a blank template. A sub-variant is a variation on
 * its parent, so it now offers the same Blank / Current choice the ordinary contents picker has —
 * and "Current" copies <b>the plot the author is standing in</b>, which is a sibling sub-variant
 * whenever they opened the menu from inside one, while the command still targets the group's root.
 * Those two ids being distinct is the part worth pinning.</p>
 */
final class NewSourcePickerSubVariantTest {

    private static List<CommandMenuEntry> entriesFor(String parentId, String sourceId) {
        return new NewSourcePickerScreen(
            NewSourcePickerScreen.Category.CONTENTS_SUB_VARIANT, null, parentId, sourceId).entries();
    }

    private static CommandMenuEntry.TypeArg typeArg(List<CommandMenuEntry> entries, int index) {
        return assertInstanceOf(CommandMenuEntry.TypeArg.class, entries.get(index));
    }

    @Test
    @DisplayName("Standing in the parent: Blank, then Current naming the parent")
    void inParent_offersBlankAndCurrent() {
        List<CommandMenuEntry> entries = entriesFor("portal", "portal");

        CommandMenuEntry.TypeArg blank = typeArg(entries, 0);
        assertEquals("Blank", blank.label());
        assertEquals("dungeontrain editor contents group new portal", blank.commandPrefix());
        assertEquals("blank", blank.commandSuffix());

        CommandMenuEntry.TypeArg current = typeArg(entries, 1);
        assertEquals("Current (portal)", current.label());
        assertEquals("dungeontrain editor contents group new portal", current.commandPrefix());
        assertEquals("portal", current.commandSuffix());

        assertInstanceOf(CommandMenuEntry.Back.class, entries.get(entries.size() - 1));
    }

    @Test
    @DisplayName("Standing in a sibling: the command still targets the parent, Current copies the sibling")
    void inSibling_targetsParentButClonesSibling() {
        List<CommandMenuEntry> entries = entriesFor("portal", "portalzig");

        // A sub-variant cannot be a group parent, so the command has to keep naming the root...
        CommandMenuEntry.TypeArg current = typeArg(entries, 1);
        assertEquals("dungeontrain editor contents group new portal", current.commandPrefix());
        // ...while the seed is what the author is actually looking at.
        assertEquals("Current (portalzig)", current.label());
        assertEquals("portalzig", current.commandSuffix());
    }

    @Test
    @DisplayName("An unknown active plot falls back to the parent, which is always cloneable")
    void blankSource_fallsBackToTheParent() {
        List<CommandMenuEntry> entries = entriesFor("portal", "");

        assertEquals("Current (portal)", typeArg(entries, 1).label());
        assertEquals("portal", typeArg(entries, 1).commandSuffix());
    }

    @Test
    @DisplayName("With nothing to clone at all, Blank stands alone rather than an unusable row")
    void noParentOrSource_offersBlankOnly() {
        List<CommandMenuEntry> entries = entriesFor("", "");

        assertEquals("Blank", typeArg(entries, 0).label());
        assertTrue(entries.stream()
                .noneMatch(e -> e instanceof CommandMenuEntry.TypeArg t && t.label().startsWith("Current")),
            "nothing to clone, so no Current row");
    }

    @Test
    @DisplayName("The 3-arg constructor still works — source defaults to the command's target")
    void legacyConstructor_defaultsSourceToCurrent() {
        List<CommandMenuEntry> entries = new NewSourcePickerScreen(
            NewSourcePickerScreen.Category.CONTENTS_SUB_VARIANT, null, "portal").entries();

        assertEquals("Current (portal)", typeArg(entries, 1).label());
    }
}
