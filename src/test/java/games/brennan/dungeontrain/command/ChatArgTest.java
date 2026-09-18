package games.brennan.dungeontrain.command;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ChatArg} exists because {@code TranslatableContents} rejects anything that is not a
 * Component / Number / Boolean / String. PR #1447 passed raw {@link BlockPos} and {@link Path}
 * values into editor feedback lines, which turned every successful "editor enter" / "editor new"
 * into a spurious "enter failed" reply. These tests pin both halves: the helper produces a plain
 * String the component accepts, and the dev-time guard that catches a raw value is live under the
 * FML-bootstrapped test runtime — so a future regression fails here, not in-game.
 */
class ChatArgTest {

    private static TranslatableContents contents(Component component) {
        return (TranslatableContents) component.getContents();
    }

    @Test
    @DisplayName("A BlockPos becomes the short 'x, y, z' form and is accepted as a translatable arg")
    void posIsAcceptedAndRendersShortForm() {
        Component c = Component.translatable("chat.dungeontrain.editor.entered_plot", "x", ChatArg.pos(new BlockPos(126, 230, 0)));
        Object[] args = contents(c).getArgs();
        assertEquals(2, args.length);
        assertInstanceOf(String.class, args[1]);
        assertEquals("126, 230, 0", args[1]);
    }

    @Test
    @DisplayName("A Path becomes its platform string and is accepted as a translatable arg")
    void pathIsAcceptedAsString() {
        Path p = Path.of("dungeontrain", "user", "weights.json");
        Component c = Component.translatable("chat.dungeontrain.editor.weight_saved", "cart", 3, ChatArg.path(p));
        Object[] args = contents(c).getArgs();
        assertInstanceOf(String.class, args[2]);
        assertEquals(p.toString(), args[2]);
    }

    @Test
    @DisplayName("Null-safe: a missing position prints 'null' rather than throwing")
    void nullPosIsSafe() {
        assertEquals("null", ChatArg.pos(null));
        assertEquals("null", ChatArg.path(null));
    }

    @Test
    @DisplayName("Regression guard: a raw BlockPos argument is rejected by TranslatableContents in dev")
    void rawBlockPosIsRejected() {
        BlockPos raw = new BlockPos(1, 2, 3);
        assertThrows(IllegalArgumentException.class,
            () -> Component.translatable("chat.dungeontrain.editor.entered_plot", "x", raw));
    }
}
