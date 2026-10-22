package games.brennan.dungeontrain.builder.relay;

import games.brennan.dungeontrain.builder.relay.WorkbenchSendEdits.Refusal;
import games.brennan.dungeontrain.editor.workbench.WorkbenchStagedBuild;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.CallStatus;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchSendEditsTest {

    private static final String DEV = "00000000-0000-0000-0000-00000000000d";
    private static final String AUTHOR = "00000000-0000-0000-0000-00000000000a";

    private static WorkbenchStagedBuild staged(int relayId, String owner, boolean mine) {
        return new WorkbenchStagedBuild("cabin-" + relayId, relayId, "contents", "", "Cabin", "", owner, "Alex",
            mine, new Vec3i(5, 5, 5), "", Map.of(), 1L, true);
    }

    @Test
    @DisplayName("the developer may send edits on somebody else's relay build, with a comment")
    void allowed() {
        assertEquals(Refusal.NONE, WorkbenchSendEdits.refusal(true, DEV, staged(42, AUTHOR, false), "moved the chest"));
    }

    @Test
    @DisplayName("refused for a non-developer, the developer's own build, no row and no comment")
    void refusals() {
        assertEquals(Refusal.NOT_DEVELOPER, WorkbenchSendEdits.refusal(false, DEV, staged(42, AUTHOR, false), "x"));
        assertEquals(Refusal.OWN_BUILD, WorkbenchSendEdits.refusal(true, DEV, staged(42, AUTHOR, true), "x"));
        // Not flagged mine, but the uuid says it is: still the developer's own.
        assertEquals(Refusal.OWN_BUILD, WorkbenchSendEdits.refusal(true, DEV, staged(42, DEV.toUpperCase(), false), "x"));
        assertEquals(Refusal.NO_RELAY_ROW, WorkbenchSendEdits.refusal(true, DEV, staged(0, AUTHOR, false), "x"));
        assertEquals(Refusal.NO_RELAY_ROW, WorkbenchSendEdits.refusal(true, DEV, staged(42, "", false), "x"));
        assertEquals(Refusal.NO_COMMENT, WorkbenchSendEdits.refusal(true, DEV, staged(42, AUTHOR, false), "  \n "));
        assertEquals(Refusal.NO_COMMENT, WorkbenchSendEdits.refusal(true, DEV, staged(42, AUTHOR, false), null));
    }

    @Test
    @DisplayName("every refusal and relay answer has its own chat key")
    void keys() {
        for (Refusal r : Refusal.values()) {
            if (r != Refusal.NONE) assertTrue(WorkbenchSendEdits.refusalKey(r).startsWith("chat.dungeontrain.workbench.send."), r.name());
        }
        assertEquals("chat.dungeontrain.workbench.send.sent", key(WorkbenchSendEdits.said(CallStatus.OK, "Alex")));
        assertEquals("chat.dungeontrain.workbench.send.unsupported", key(WorkbenchSendEdits.said(CallStatus.UNKNOWN, "Alex")));
        assertEquals("chat.dungeontrain.workbench.send.failed", key(WorkbenchSendEdits.said(CallStatus.TIMEOUT, "Alex")));
    }

    @Test
    @DisplayName("the owner's accept and keep answers read differently, and a missing edit says so")
    void ownerAnswers() {
        assertEquals("gui.dungeontrain.builder.profile.suggestion.accepted",
            key(BuilderRelayUpload.suggestionMessage(CallStatus.OK, true)));
        assertEquals("gui.dungeontrain.builder.profile.suggestion.kept",
            key(BuilderRelayUpload.suggestionMessage(CallStatus.OK, false)));
        assertEquals("gui.dungeontrain.builder.profile.suggestion.none",
            key(BuilderRelayUpload.suggestionMessage(CallStatus.UNKNOWN, true)));
    }

    private static String key(net.minecraft.network.chat.Component c) {
        return ((TranslatableContents) c.getContents()).getKey();
    }
}
