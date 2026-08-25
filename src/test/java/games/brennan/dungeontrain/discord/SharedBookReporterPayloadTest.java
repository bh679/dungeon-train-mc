package games.brennan.dungeontrain.discord;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The idempotency key on the {@code /books/submit} body — the field that tells the relay a re-sent
 * queued upload apart from a writer genuinely uploading the same book twice. The rest of the payload
 * shape is covered by {@link SharedBookReporterTest}.
 */
class SharedBookReporterPayloadTest {

    private static final String UUID_HEX = "069a79f444e94726a5befca90e38aaf5";

    @Test
    void theKeyIsCarriedVerbatimSoEveryRetrySendsTheSameOne() {
        assertEquals("item-1", SharedBookReporter
                .buildPayload(UUID_HEX, "Steve", "A Gentle Tale", List.of("page one"), "en_us", "item-1")
                .get("key").getAsString());
    }

    @Test
    void aBlankKeyIsOmittedRatherThanSentEmpty() {
        // An empty key matches nothing on the relay anyway; leaving the field out keeps the body
        // byte-identical to what older jars sent, which is what an older relay expects.
        assertFalse(SharedBookReporter.buildPayload(UUID_HEX, "Steve", "T", List.of("p"), "en_us", "")
                .has("key"));
        assertFalse(SharedBookReporter.buildPayload(UUID_HEX, "Steve", "T", List.of("p"), "en_us", null)
                .has("key"));
    }
}
