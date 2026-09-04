package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import games.brennan.dungeontrain.net.BuilderProfilePacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a builder's uploads look like through the editor screen's tabs.
 *
 * <p>The mapping is the load-bearing part: a tab that admits the wrong relay kind shows a portal
 * room under Carriages, and one that admits too little hides work the reviewer came to see.</p>
 */
final class EditorCreatorBuildsTest {

    private static BuilderProfilePacket.Entry entry(String kind, String name, String review) {
        return new BuilderProfilePacket.Entry(7, kind, "", name, false, "", review, "", 3,
            false, "uuid", "Edda");
    }

    @Test
    @DisplayName("the tabs group relay kinds the way they group local templates")
    void tabsGroupKinds() {
        assertTrue(EditorCreatorBuilds.admits(EditorScreenPage.CARRIAGES, BuilderRelayKinds.CARRIAGE));
        assertTrue(EditorCreatorBuilds.admits(EditorScreenPage.CARRIAGES, BuilderRelayKinds.CARRIAGE_GROUP));
        // Parts browse under Carriages for local templates too — see EditorScreenPage.forCategory.
        assertTrue(EditorCreatorBuilds.admits(EditorScreenPage.CARRIAGES, BuilderRelayKinds.PART));
        assertFalse(EditorCreatorBuilds.admits(EditorScreenPage.CARRIAGES, BuilderRelayKinds.CONTENTS));

        assertTrue(EditorCreatorBuilds.admits(EditorScreenPage.CONTENTS, BuilderRelayKinds.CONTENTS));
        assertTrue(EditorCreatorBuilds.admits(EditorScreenPage.TRACKS, BuilderRelayKinds.TRACK));
        assertTrue(EditorCreatorBuilds.admits(EditorScreenPage.DIMENSIONS, BuilderRelayKinds.PORTAL_ROOM));
        assertFalse(EditorCreatorBuilds.admits(EditorScreenPage.DIMENSIONS, BuilderRelayKinds.TRACK));
    }

    @Test
    @DisplayName("All shows every kind")
    void allShowsEverything() {
        for (String kind : new String[] {BuilderRelayKinds.CARRIAGE, BuilderRelayKinds.CONTENTS,
                BuilderRelayKinds.PART, BuilderRelayKinds.TRACK, BuilderRelayKinds.PORTAL_ROOM}) {
            assertTrue(EditorCreatorBuilds.admits(EditorScreenPage.ALL, kind), kind);
        }
    }

    @Test
    @DisplayName("a nameless build is captioned by its relay id, so no tile is blank")
    void namelessBuildKeepsAnIdentity() {
        assertEquals("#7", EditorCreatorBuilds.label(entry(BuilderRelayKinds.CARRIAGE, "", "")));
    }

    @Test
    @DisplayName("review states map onto the words My Builds already uses")
    void reviewStatesReuseTheProfileWording() {
        assertEquals("gui.dungeontrain.builder.profile.status.submitted",
            EditorCreatorBuilds.reviewKey(BuilderReviewState.SUBMITTED));
        assertEquals("gui.dungeontrain.builder.profile.status.accepted",
            EditorCreatorBuilds.reviewKey(BuilderReviewState.ACCEPTED));
        assertEquals("gui.dungeontrain.builder.profile.status.declined",
            EditorCreatorBuilds.reviewKey(BuilderReviewState.DECLINED));
        // Anything the relay grows before this build ships reads as never-asked, not as a crash.
        assertEquals("gui.dungeontrain.builder.profile.status.none",
            EditorCreatorBuilds.reviewKey("something_new"));
        assertEquals("gui.dungeontrain.builder.profile.status.none", EditorCreatorBuilds.reviewKey(null));
    }

    @Test
    @DisplayName("every kind names a type the detail pane can label")
    void everyKindHasALabel() {
        assertEquals("gui.dungeontrain.builder.profile.type.part",
            EditorCreatorBuilds.kindKey(BuilderRelayKinds.PART));
        assertEquals("gui.dungeontrain.builder.profile.type.portal_room",
            EditorCreatorBuilds.kindKey(BuilderRelayKinds.PORTAL_ROOM));
        assertEquals("gui.dungeontrain.builder.profile.type.carriage",
            EditorCreatorBuilds.kindKey("something_new"));
    }
}
