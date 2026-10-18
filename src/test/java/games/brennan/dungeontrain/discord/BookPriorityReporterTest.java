package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The {@code /books/credits/spend} body: the wallet is the (uuid, Minecraft name) pair. */
class BookPriorityReporterTest {

    @Test
    void theSpendCarriesTheWalletAndTheBook() {
        JsonObject body = BookPriorityReporter.buildPayload("069a79f444e94726a5befca90e38aaf5", "Steve", "42");
        assertEquals("069a79f444e94726a5befca90e38aaf5", body.get("uuid").getAsString());
        assertEquals("Steve", body.get("name").getAsString());
        assertEquals("42", body.get("id").getAsString());
        assertEquals("", BookPriorityReporter.buildPayload("u", null, "1").get("name").getAsString());
    }
}
