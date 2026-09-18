package games.brennan.dungeontrain.command;

import net.minecraft.core.BlockPos;

import java.nio.file.Path;

/**
 * Formats values for use as {@code Component.translatable(key, args...)} arguments.
 *
 * <p>{@code TranslatableContents} only accepts {@code Component}, {@code Number}, {@code Boolean}
 * or {@code String} arguments. Anything else — a {@link BlockPos}, a {@link Path}, an enum — throws
 * {@code IllegalArgumentException} at construction in dev and fails the chat packet codec in
 * production, so the feedback line is lost either way. Because {@code sendSuccess(() -> ...)}
 * evaluates the supplier inside the call, that throw surfaces as a spurious
 * "&lt;command&gt; failed" reply from the surrounding catch even though the command itself
 * succeeded (regression from #1447). Route non-primitive values through here.
 */
public final class ChatArg {

    private ChatArg() {
    }

    /** Chat form of a block position: {@code "126, 230, 0"}. */
    public static String pos(BlockPos pos) {
        return pos == null ? "null" : pos.toShortString();
    }

    /** Chat form of a file path — the platform string, as the pre-#1447 concatenation printed it. */
    public static String path(Path path) {
        return String.valueOf(path);
    }
}
