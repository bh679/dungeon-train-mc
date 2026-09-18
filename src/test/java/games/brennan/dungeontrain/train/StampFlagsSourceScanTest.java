package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.RepoPaths;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards {@link CarriageStampGuard#STAMP_FLAGS} as the <em>only</em> flag any template
 * {@code placeInWorld} in {@code src/main/java} may pass. Flag 3 ({@code UPDATE_ALL}) pops every
 * Fast Paintings picture mid-stamp (see the constant's javadoc); PR #1451 fixed twelve sites by
 * hand, and this scan is what stops a thirteenth from quietly regressing them.
 *
 * <p>Scans source text rather than bytecode so the failure names the file and line. Comments and
 * javadoc are stripped first — two javadocs mention {@code placeInWorld(} in prose.</p>
 */
final class StampFlagsSourceScanTest {

    /** Every arg form that is allowed. The constant itself, or its unqualified name inside its owner. */
    private static final List<String> ALLOWED = List.of("CarriageStampGuard.STAMP_FLAGS", "STAMP_FLAGS");
    /** Fewer hits than this means the scanner broke, not that the code got cleaner. */
    private static final int MIN_EXPECTED_CALLS = 10;

    private static final Pattern CALL = Pattern.compile("\\.placeInWorld\\(");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    @Test
    void stampFlagsIsUpdateClients() {
        assertEquals(Block.UPDATE_CLIENTS, CarriageStampGuard.STAMP_FLAGS, "STAMP_FLAGS must stay UPDATE_CLIENTS (2)");
        assertTrue((CarriageStampGuard.STAMP_FLAGS & Block.UPDATE_NEIGHBORS) == 0,
            "STAMP_FLAGS must not carry UPDATE_NEIGHBORS — that is what pops Fast Paintings");
    }

    @Test
    void everyPlaceInWorldUsesStampFlags() throws IOException {
        Path root = RepoPaths.root().resolve("src/main/java");
        List<String> offenders = new ArrayList<>();
        int calls = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String source = stripComments(Files.readString(file));
                Matcher m = CALL.matcher(source);
                while (m.find()) {
                    calls++;
                    String lastArg = lastArgument(source, m.end());
                    if (!ALLOWED.contains(lastArg)) {
                        int line = 1 + (int) source.substring(0, m.start()).chars().filter(c -> c == '\n').count();
                        offenders.add(root.relativize(file) + ":" + line + " → " + lastArg);
                    }
                }
            }
        }
        assertTrue(calls >= MIN_EXPECTED_CALLS, "Scanner found only " + calls + " placeInWorld calls — is it broken?");
        assertTrue(offenders.isEmpty(), () -> "placeInWorld must pass CarriageStampGuard.STAMP_FLAGS "
            + "(UPDATE_CLIENTS) — flag 3 / UPDATE_ALL pops Fast Paintings mid-stamp. Offenders:\n  "
            + String.join("\n  ", offenders));
    }

    /**
     * Comments are blanked (newlines kept) so line numbers in the report stay right and no prose
     * mention of {@code placeInWorld(} counts as a call.
     */
    private static String stripComments(String source) {
        String noBlock = BLOCK_COMMENT.matcher(source).replaceAll(r -> r.group().replaceAll("[^\\n]", " "));
        return LINE_COMMENT.matcher(noBlock).replaceAll("");
    }

    /** The final top-level argument of the call whose opening paren precedes {@code from}. */
    private static String lastArgument(String source, int from) {
        int depth = 0;
        int argStart = from;
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) return source.substring(argStart, i).trim();
                depth--;
            } else if (c == ',' && depth == 0) {
                argStart = i + 1;
            }
        }
        throw new IllegalStateException("Unbalanced placeInWorld call at offset " + from);
    }
}
