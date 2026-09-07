package games.brennan.dungeontrain.client.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how each {@link CommandMenuEntry} kind routes through {@link MenuEntryDispatcher} — the
 * table both the panels and the inventory-style editor screen depend on.
 */
final class MenuEntryDispatcherTest {

    /** Records every host call as a line, so a test asserts the whole sequence. */
    private static final class Recorder implements MenuEntryDispatcher.Host {
        final List<String> log = new ArrayList<>();
        MenuScreen drilled;

        @Override public void runAndClose(String command) { log.add("close:" + command); }
        @Override public void runAndStay(String command) { log.add("stay:" + command); }
        @Override public void drillIn(MenuScreen target) { drilled = target; log.add("drill"); }
        @Override public void goBack() { log.add("back"); }
        @Override public void beginTyping(String argName, String prefix, String suffix, String initial) {
            log.add("type:" + argName + "|" + prefix + "|" + suffix + "|" + initial);
        }
    }

    private static final MenuScreen TARGET = new MenuScreen() {
        @Override public String title() { return "t"; }
        @Override public List<CommandMenuEntry> entries() { return List.of(); }
    };

    @Test
    @DisplayName("Run closes after the command; Stay keeps the surface open")
    void runAndStay() {
        Recorder h = new Recorder();
        MenuEntryDispatcher.dispatch(new CommandMenuEntry.Run("r", "dungeontrain editor exit"), 0, h, false);
        MenuEntryDispatcher.dispatch(new CommandMenuEntry.Stay("s", "dungeontrain editor weight x inc"), 0, h, false);
        assertEquals(List.of("close:dungeontrain editor exit", "stay:dungeontrain editor weight x inc"), h.log);
    }

    @Test
    @DisplayName("DrillIn hands the target screen to the host; Back goes back")
    void drillAndBack() {
        Recorder h = new Recorder();
        MenuEntryDispatcher.dispatch(new CommandMenuEntry.DrillIn("d", TARGET), 0, h, false);
        MenuEntryDispatcher.dispatch(new CommandMenuEntry.Back("<"), 0, h, false);
        assertSame(TARGET, h.drilled);
        assertEquals(List.of("drill", "back"), h.log);
    }

    @Test
    @DisplayName("TypeArg opens a typing field with its prefix, suffix and pre-filled buffer")
    void typeArg() {
        Recorder h = new Recorder();
        MenuEntryDispatcher.dispatch(
            new CommandMenuEntry.TypeArg("Rename", "new_name", "dungeontrain editor save", "", "old"), 0, h, false);
        assertEquals(List.of("type:new_name|dungeontrain editor save||old"), h.log);
    }

    @Test
    @DisplayName("Toggle sends the off command when on, the on command when off, and others under Shift")
    void toggle() {
        Recorder h = new Recorder();
        CommandMenuEntry on = new CommandMenuEntry.Toggle("t", true, "cmd on", "cmd off", false, "cmd others");
        CommandMenuEntry off = new CommandMenuEntry.Toggle("t", false, "cmd on", "cmd off", false, "cmd others");
        MenuEntryDispatcher.dispatch(on, 0, h, false);
        MenuEntryDispatcher.dispatch(off, 0, h, false);
        MenuEntryDispatcher.dispatch(on, 0, h, true);
        assertEquals(List.of("stay:cmd off", "stay:cmd on", "stay:cmd others"), h.log);
    }

    @Test
    @DisplayName("Split, Triple and Quad recurse into the clicked cell")
    void multiCell() {
        Recorder h = new Recorder();
        CommandMenuEntry a = new CommandMenuEntry.Stay("a", "a");
        CommandMenuEntry b = new CommandMenuEntry.Stay("b", "b");
        CommandMenuEntry c = new CommandMenuEntry.Stay("c", "c");
        CommandMenuEntry d = new CommandMenuEntry.Stay("d", "d");
        MenuEntryDispatcher.dispatch(new CommandMenuEntry.Split(a, b, 0.5), 1, h, false);
        MenuEntryDispatcher.dispatch(new CommandMenuEntry.Triple(a, b, c, 0.1, 0.9), 2, h, false);
        MenuEntryDispatcher.dispatch(new CommandMenuEntry.Quad(a, b, c, d, 0.25, 0.5, 0.75), 3, h, false);
        MenuEntryDispatcher.dispatch(new CommandMenuEntry.Quad(a, b, c, d, 0.25, 0.5, 0.75), 0, h, false);
        assertEquals(List.of("stay:b", "stay:c", "stay:d", "stay:a"), h.log);
    }

    @Test
    @DisplayName("Labels, Loading, saved SaveActions and null are inert")
    void inertEntries() {
        Recorder h = new Recorder();
        boolean[] clicked = {false};
        MenuEntryDispatcher.dispatch(new CommandMenuEntry.Label("l"), 0, h, false);
        MenuEntryDispatcher.dispatch(new CommandMenuEntry.Loading("…"), 0, h, false);
        MenuEntryDispatcher.dispatch(new CommandMenuEntry.SaveAction("s", () -> clicked[0] = true, true), 0, h, false);
        MenuEntryDispatcher.dispatch(null, 0, h, false);
        assertTrue(h.log.isEmpty());
        assertTrue(!clicked[0]);
        MenuEntryDispatcher.dispatch(new CommandMenuEntry.SaveAction("s", () -> clicked[0] = true, false), 0, h, false);
        assertTrue(clicked[0]);
    }

    @Test
    @DisplayName("ClientAction runs its runnable without touching the host")
    void clientAction() {
        Recorder h = new Recorder();
        boolean[] ran = {false};
        MenuEntryDispatcher.dispatch(new CommandMenuEntry.ClientAction("c", () -> ran[0] = true), 0, h, false);
        assertTrue(ran[0]);
        assertTrue(h.log.isEmpty());
    }
}
