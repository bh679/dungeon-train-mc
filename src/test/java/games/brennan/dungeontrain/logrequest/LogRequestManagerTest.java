package games.brennan.dungeontrain.logrequest;

import games.brennan.dungeontrain.logrequest.DeliveryFailureReport.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogRequestManagerTest {

    @Test
    @DisplayName("/bug and !bug are the command, in any case and padding; nothing else is")
    void command() {
        assertTrue(LogRequestManager.isCommand("/bug"));
        assertTrue(LogRequestManager.isCommand("  /BUG "));
        assertTrue(LogRequestManager.isCommand("!bug"));
        assertFalse(LogRequestManager.isCommand("/bug please"));
        assertFalse(LogRequestManager.isCommand("is this a bug?"));
        assertFalse(LogRequestManager.isCommand(null));
    }

    @Test
    @DisplayName("Kid mode wins over Developer chat off, which wins over an already-pending request")
    void blockOrder() {
        assertEquals(Reason.KID_MODE, LogRequestManager.blockReason(true, true, true));
        assertEquals(Reason.DEV_CHAT_OFF, LogRequestManager.blockReason(false, true, true));
        assertEquals(Reason.ALREADY_PENDING, LogRequestManager.blockReason(false, false, true));
        assertNull(LogRequestManager.blockReason(false, false, false));
    }

    @Test
    @DisplayName("the reply reads \"Message didn't land (name) — why\"")
    void replyLine() {
        assertEquals("Message didn't land (Steve) — Kid mode is on",
                DeliveryFailureReport.line("Steve", Reason.KID_MODE));
        assertEquals("Message didn't land — Developer chat is switched off",
                DeliveryFailureReport.line(null, Reason.DEV_CHAT_OFF));
    }
}
