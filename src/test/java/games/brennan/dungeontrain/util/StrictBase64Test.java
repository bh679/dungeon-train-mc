package games.brennan.dungeontrain.util;

import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link StrictBase64} matches the JDK on canonical input, rejects everything else, and is the only decoder in use. */
class StrictBase64Test {

    @Test
    void roundTripsEveryLengthAgainstTheJdkEncoder() {
        Random random = new Random(20261118L);
        for (int length = 0; length <= 2000; length++) {
            byte[] data = new byte[length];
            random.nextBytes(data);
            String encoded = Base64.getEncoder().encodeToString(data);
            assertArrayEquals(data, StrictBase64.decode(encoded), "length " + length);
        }
    }

    @Test
    void decodesALargeBlob() {
        byte[] data = new byte[700_000];
        new Random(7L).nextBytes(data);
        assertArrayEquals(data, StrictBase64.decode(Base64.getEncoder().encodeToString(data)));
    }

    @Test
    void emptyStringIsEmpty() {
        assertEquals(0, StrictBase64.decode("").length);
    }

    @Test
    void rejectsTheAdvisoryTriggerTails() {
        for (int prefix : new int[] {64, 128}) {
            String as = "A".repeat(prefix);
            for (String tail : new String[] {"=", "==", "X=", "X==", "XY=", "===", "===="}) {
                String input = as + tail;
                assertThrows(IllegalArgumentException.class, () -> StrictBase64.decode(input), input);
            }
        }
    }

    @Test
    void rejectsNonCanonicalShapes() {
        String[] bad = {
                "A", "AB", "ABC",          // length not a multiple of 4
                "A===", "====",            // too much padding
                "AB=C", "A=BC", "=ABC",    // padding before the end
                "AB-_", "ab_-",            // URL-safe alphabet
                "AB C", "ABC\n", "AB\tC",  // whitespace
                "ABCé", "ABC\u0000",  // non-alphabet characters
        };
        for (String input : bad) {
            assertThrows(IllegalArgumentException.class, () -> StrictBase64.decode(input), input);
        }
        assertThrows(IllegalArgumentException.class, () -> StrictBase64.decode(null));
    }

    @Test
    void acceptsBothPaddedTails() {
        assertArrayEquals("a".getBytes(StandardCharsets.US_ASCII), StrictBase64.decode("YQ=="));
        assertArrayEquals("ab".getBytes(StandardCharsets.US_ASCII), StrictBase64.decode("YWI="));
        assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII), StrictBase64.decode("YWJj"));
    }

    /** Matches a call to any JDK Base64 decoder (the encoder is fine). */
    private static final Pattern JDK_DECODER = Pattern.compile("Base64\\s*\\.\\s*get(Mime|Url)?Decoder\\s*\\(");

    @Test
    void noOtherMainClassUsesAJdkDecoder() throws IOException {
        Path mainJava = RepoPaths.root().resolve("src/main/java");
        assertTrue(Files.isDirectory(mainJava), "missing " + mainJava);
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainJava)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                if (file.getFileName().toString().equals("StrictBase64.java")) continue;
                if (JDK_DECODER.matcher(Files.readString(file)).find()) offenders.add(mainJava.relativize(file).toString());
            }
        }
        assertEquals(List.of(), offenders, "use StrictBase64.decode instead of java.util.Base64's decoder (CVE-2026-114938)");
    }
}
