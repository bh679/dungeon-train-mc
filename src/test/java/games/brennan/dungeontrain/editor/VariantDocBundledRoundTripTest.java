package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.RepoPaths;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every bundled {@code *.variants.json} (carriage, contents, part, whole, track, portal room,
 * tunnel, chunk frame) round-trips through {@link VariantDoc}.
 *
 * <p>The writer migrates on read: the schema version, the legacy command-block sentinel, a derived
 * {@code "half"} or {@code "active"}, a reference row's refreshed {@code "groupStates"}. So the
 * contract is that one parse-then-write is the migration the editor has always made, and a second
 * one changes nothing. Before the five per-kind writers were deleted, each was run against this
 * class over every bundled file and produced identical output.</p>
 */
final class VariantDocBundledRoundTripTest {

    private static final Path DATA = RepoPaths.resources().resolve("data/dungeontrain");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Tunnels default X+Z mirror on; everything else all-off. The store derives this from the address. */
    static MirrorDefaults defaultsFor(String rel) {
        return rel.startsWith("tunnels/") ? MirrorDefaults.TUNNEL : MirrorDefaults.NONE;
    }

    static List<Path> bundledSidecars() throws IOException {
        try (Stream<Path> s = Files.walk(DATA)) {
            return s.filter(p -> p.toString().endsWith(VariantAddress.EXT)).sorted().toList();
        }
    }

    private static String write(String text, String rel) {
        return VariantDoc.parse(new StringReader(text), defaultsFor(rel), VariantPick.PLAIN, rel, "test").toJsonText();
    }

    @Test
    @DisplayName("every bundled sidecar is a fixed point of parse-then-write after its one-time migration")
    void everyBundledSidecarIsAFixedPoint() throws IOException {
        List<Path> files = bundledSidecars();
        assertTrue(files.size() > 500, "expected the shipped sidecars, found " + files.size());
        List<String> failures = new ArrayList<>();
        int identical = 0;
        for (Path f : files) {
            String rel = DATA.relativize(f).toString();
            String text = Files.readString(f, StandardCharsets.UTF_8);
            String once = write(text, rel);
            if (once.equals(text)) identical++;
            String twice = write(once, rel);
            if (!twice.equals(once)) failures.add(rel + firstDifference(once, twice));
        }
        System.out.println("[VariantDocBundledRoundTripTest] " + files.size() + " sidecars, "
            + identical + " byte-identical to disk, all fixed points");
        assertTrue(identical > 0, "expected some sidecars already in canonical form");
        assertEquals(List.of(), failures, "sidecars whose second save differs from their first");
    }

    /** The first line that differs, for the failure message. */
    private static String firstDifference(String expected, String actual) {
        String[] a = expected.split("\n"), b = actual.split("\n");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String x = i < a.length ? a[i] : "<eof>", y = i < b.length ? b[i] : "<eof>";
            if (!x.equals(y)) return "\n    first:  " + x + "\n    second: " + y;
        }
        return " (differs only in length)";
    }
}
