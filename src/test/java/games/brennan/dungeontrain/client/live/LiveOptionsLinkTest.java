package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.chat.ChatClick;
import games.brennan.dungeontrain.client.ClientOptionsTab;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** An options link is a chat-click action whose tab and row survive the round trip. */
final class LiveOptionsLinkTest {

    @Test
    @DisplayName("Every row name fits a chat click, and parses back to the same tab and row")
    void linkValueRoundTrips() {
        for (ClientOptionsTab.Row row : ClientOptionsTab.Row.values()) {
            List<String> parts = ChatClick.parse(LiveOptionsCommand.linkValue(ClientOptionsTab.STREAM, row));
            assertEquals(List.of(LiveOptionsCommand.OPTIONS_ACTION, "STREAM", row.name()), parts);
            assertEquals(ClientOptionsTab.STREAM, ClientOptionsTab.tabNamed(parts.get(1)));
            assertEquals(row, ClientOptionsTab.rowNamed(parts.get(2)));
        }
    }
}
