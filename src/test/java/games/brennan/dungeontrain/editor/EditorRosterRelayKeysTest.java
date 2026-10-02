package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.builder.relay.BuilderRelayBuilds;
import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.editor.relay.EditorRelayWrite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The relay keys the editor roster looks a template's row up by must be the keys an upload files
 * it under — a miss reads as "never uploaded" and greys the Submit icon on a build that is.
 */
class EditorRosterRelayKeysTest {

    @Test
    void wholeRoomFindsTheEditorUploadThenAnOlderInstall() {
        List<String> keys = EditorRoster.relayKeysFor(PlotCategory.WHOLE.id(), "", "kitchen1", "kitchen1");
        assertEquals(List.of(
                BuilderRelayBuilds.keyOf(BuilderRelayKinds.CARRIAGE, EditorRelayWrite.WHOLE_ROOM_SUBKIND, "kitchen1"),
                BuilderRelayBuilds.keyOf(BuilderRelayKinds.CARRIAGE, "", "kitchen1")),
            keys);
    }

    @Test
    void carriageShellKeepsItsPlainKey() {
        assertEquals(List.of(BuilderRelayBuilds.keyOf(BuilderRelayKinds.CARRIAGE, "", "brick")),
            EditorRoster.relayKeysFor(EditorCategory.CARRIAGES.id(), "", "brick", "brick"));
    }

    @Test
    void contentsKeyIsUnchanged() {
        assertEquals(List.of(BuilderRelayBuilds.keyOf(BuilderRelayKinds.CONTENTS, "", "kitchen1")),
            EditorRoster.relayKeysFor(EditorCategory.CONTENTS.id(), "room", "kitchen1", "kitchen1"));
    }
}
