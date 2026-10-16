package games.brennan.dungeontrain.client.menu.number;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pins the gesture table and the typed-value rules every editor number shares. */
final class NumberFieldTest {

    @Test
    @DisplayName("click steps up, shift-click and right-click step down, cmd always types")
    void gestures() {
        assertEquals(NumberGesture.INC, NumberGesture.resolve(0, false, false));
        assertEquals(NumberGesture.DEC, NumberGesture.resolve(0, true, false));
        assertEquals(NumberGesture.DEC, NumberGesture.resolve(1, false, false));
        assertEquals(NumberGesture.DEC, NumberGesture.resolve(1, true, false));
        assertEquals(NumberGesture.TYPE, NumberGesture.resolve(0, false, true));
        assertEquals(NumberGesture.TYPE, NumberGesture.resolve(1, true, true));
    }

    @Test
    @DisplayName("a command field sends inc, dec and the typed number on its prefix")
    void commandField() {
        List<String> sent = new ArrayList<>();
        NumberField f = NumberField.ofCommand("Weight", "dungeontrain editor weight a", 3, 0, 100, false, sent::add);
        NumberStepper.click(f, NumberGesture.INC, x -> { });
        NumberStepper.click(f, 1, false, false, x -> { });
        f.set().accept(40);
        assertEquals(List.of("dungeontrain editor weight a inc", "dungeontrain editor weight a dec",
            "dungeontrain editor weight a 40"), sent);
    }

    @Test
    @DisplayName("cmd-click hands the field to the typing surface instead of stepping")
    void typeGoesToTyping() {
        List<String> sent = new ArrayList<>();
        NumberField f = NumberField.ofCommand("Weight", "w", 3, 0, 100, false, sent::add);
        List<NumberField> typed = new ArrayList<>();
        NumberStepper.click(f, 0, false, true, typed::add);
        assertEquals(List.of(f), typed);
        assertEquals(List.of(), sent);
    }

    @Test
    @DisplayName("typed values must be in range; blank only means 'all' on an all-capable field")
    void parse() {
        NumberField bounded = NumberField.ofCommand("t", "p", 5, 1, 10, false, s -> { });
        assertEquals(7, bounded.parse("7"));
        assertNull(bounded.parse(""));
        assertNull(bounded.parse("0"));
        assertNull(bounded.parse("11"));
        assertNull(bounded.parse("x"));

        NumberField max = NumberField.ofCommand("t", "p", NumberField.ALL, 0, 1000, true, s -> { });
        assertEquals(NumberField.ALL, max.parse(""));
        assertEquals(NumberField.ALL, max.parse("-1"));
        assertEquals(0, max.parse("0"));
        assertEquals("", max.typedText());
        assertEquals("4", NumberField.ofCommand("t", "p", 4, 0, 9, true, s -> { }).typedText());
    }
}
