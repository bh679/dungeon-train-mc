package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The {@code /books/credits/boost} body: a spend's wallet and book, plus the boost's own nonce. */
class BookBoostReporterTest {

    @Test
    void theBoostCarriesTheWalletTheBookAndItsNonce() {
        JsonObject body = BookBoostReporter.buildPayload("069a79f444e94726a5befca90e38aaf5", "Alex", "42", "abc123");
        assertEquals("069a79f444e94726a5befca90e38aaf5", body.get("uuid").getAsString());
        assertEquals("Alex", body.get("name").getAsString());
        assertEquals("42", body.get("id").getAsString());
        assertEquals("abc123", body.get("nonce").getAsString());
    }
}
