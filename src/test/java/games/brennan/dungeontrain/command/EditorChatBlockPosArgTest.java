package games.brennan.dungeontrain.command;

import games.brennan.dungeontrain.RepoPaths;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the editor "entered / created … plot at %s" chat lines against passing a raw {@link BlockPos}
 * to {@link Component#translatable}.
 *
 * <p>{@code TranslatableContents} only accepts {@code Component}/{@code Number}/{@code Boolean}/{@code String}
 * arguments. The i18n pass in #1447 turned {@code "… at " + plotOrigin} into a translatable arg and kept the
 * {@code BlockPos}, so every editor enter threw {@code IllegalArgumentException} <em>after</em> the teleport
 * and printed the red "enter failed" line instead of the green "entered" one.</p>
 */
class EditorChatBlockPosArgTest {

    private static final Path EDITOR_COMMAND =
        RepoPaths.root().resolve("src/main/java/games/brennan/dungeontrain/command/EditorCommand.java");

    /** A translatable call whose arg list contains a {@code plotOrigin…(…)} call not followed by {@code .toShortString()}. */
    private static final Pattern RAW_PLOT_ORIGIN_ARG =
        Pattern.compile("Component\\.translatable\\(.*plotOrigin(?:Adjunct)?\\([^()]*\\)(?!\\.toShortString\\(\\))");

    @Test
    void translatableRejectsRawBlockPos() {
        BlockPos pos = new BlockPos(234, 230, 11);
        assertThrows(IllegalArgumentException.class, () -> Component.translatable("k", pos));
    }

    @Test
    void translatableAcceptsShortString() {
        BlockPos pos = new BlockPos(234, 230, 11);
        assertEquals("234, 230, 11", pos.toShortString());
        assertDoesNotThrow(() -> Component.translatable("k", pos.toShortString()));
    }

    @Test
    void noEnterLinePassesRawPlotOrigin() throws IOException {
        assertTrue(Files.isRegularFile(EDITOR_COMMAND), "EditorCommand.java not found at " + EDITOR_COMMAND);
        List<String> offenders = Files.readAllLines(EDITOR_COMMAND).stream()
            .filter(line -> RAW_PLOT_ORIGIN_ARG.matcher(line).find())
            .toList();
        assertTrue(offenders.isEmpty(),
            "raw BlockPos passed to Component.translatable — wrap in .toShortString():\n" + String.join("\n", offenders));
    }
}
