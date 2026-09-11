package games.brennan.dungeontrain.builder.relay;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.editor.CarriageContentsGroupStore;
import games.brennan.dungeontrain.editor.TrackVariantGroupStore;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantGroup;
import games.brennan.dungeontrain.train.CarriageContentsGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The refusals: every join that would build a tree the editor's own {@code group add} refuses comes
 * back false and writes nothing. The successful arm writes to the config dir and is exercised
 * in-game; what it writes for a fresh parent is pinned by {@link #freshParentShape}.
 */
final class BuilderRelaySubVariantTest {

    @AfterEach
    void forgetInjectedGroups() {
        CarriageContentsGroupStore.clearCache();
        TrackVariantGroupStore.clearCache();
    }

    @Test
    @DisplayName("only contents and portal rooms have parents to land under")
    void supportedKinds() {
        assertTrue(BuilderRelaySubVariant.supports(BuilderPhotoPaths.Kind.CONTENTS));
        assertTrue(BuilderRelaySubVariant.supports(BuilderPhotoPaths.Kind.PORTAL_ROOM));
        assertFalse(BuilderRelaySubVariant.supports(BuilderPhotoPaths.Kind.CARRIAGE), "no carriage groups yet");
        assertFalse(BuilderRelaySubVariant.supports(BuilderPhotoPaths.Kind.CARRIAGE_GROUP));
        assertFalse(BuilderRelaySubVariant.supports(BuilderPhotoPaths.Kind.PART));
        assertFalse(BuilderRelaySubVariant.supports(BuilderPhotoPaths.Kind.TRACK));
        assertFalse(BuilderRelaySubVariant.supports(null));
    }

    @Test
    @DisplayName("blank ids, unsupported kinds and self-parenting are refused before anything is looked up")
    void trivialRefusals() {
        assertFalse(BuilderRelaySubVariant.join(null, BuilderPhotoPaths.Kind.CARRIAGE, "cabin", "user_builds", null));
        assertFalse(BuilderRelaySubVariant.join(null, BuilderPhotoPaths.Kind.CONTENTS, "", "user_builds", null));
        assertFalse(BuilderRelaySubVariant.join(null, BuilderPhotoPaths.Kind.CONTENTS, "cabin", "", null));
        assertFalse(BuilderRelaySubVariant.join(null, BuilderPhotoPaths.Kind.CONTENTS, "cabin", null, null));
        assertFalse(BuilderRelaySubVariant.join(null, BuilderPhotoPaths.Kind.CONTENTS, "Cabin", "cabin", null),
            "ids are case-folded before the self check");
    }

    @Test
    @DisplayName("contents: a build that is itself a group cannot become a member (single-hop nesting)")
    void contentsNestedGroupRefused() {
        CarriageContentsGroupStore.injectForTesting("cabin",
            new CarriageContentsGroup(List.of(new CarriageContentsGroup.Member("cabin_b", 1))));
        assertFalse(BuilderRelaySubVariant.join(null, BuilderPhotoPaths.Kind.CONTENTS, "cabin", "user_builds", null));
    }

    @Test
    @DisplayName("contents: a parent that is somebody's member cannot take members (no cycles)")
    void contentsParentIsMemberRefused() {
        CarriageContentsGroupStore.injectForTesting("maze",
            new CarriageContentsGroup(List.of(new CarriageContentsGroup.Member("copper", 1))));
        assertFalse(BuilderRelaySubVariant.join(null, BuilderPhotoPaths.Kind.CONTENTS, "cabin", "copper", null));
    }

    @Test
    @DisplayName("contents: a build already filed under another parent is not moved by a load")
    void contentsAlreadyElsewhereRefused() {
        CarriageContentsGroupStore.injectForTesting("maze",
            new CarriageContentsGroup(List.of(new CarriageContentsGroup.Member("cabin", 1))));
        assertFalse(BuilderRelaySubVariant.join(null, BuilderPhotoPaths.Kind.CONTENTS, "cabin", "user_builds", null));
    }

    @Test
    @DisplayName("contents: the built-in default contents cannot be a parent")
    void contentsBuiltinParentRefused() {
        assertFalse(BuilderRelaySubVariant.join(null, BuilderPhotoPaths.Kind.CONTENTS, "cabin", "default", null));
    }

    @Test
    @DisplayName("rooms: default is neither parent nor member; nesting and cycles are refused")
    void roomRefusals() {
        BuilderPhotoPaths.Kind room = BuilderPhotoPaths.Kind.PORTAL_ROOM;
        assertFalse(BuilderRelaySubVariant.join(null, room, "cabin", TrackKind.DEFAULT_NAME, null));
        assertFalse(BuilderRelaySubVariant.join(null, room, TrackKind.DEFAULT_NAME, "user_builds", null));
        TrackVariantGroupStore.injectForTesting(TrackKind.PORTAL_ROOM, "cabin",
            new TrackVariantGroup(List.of(new TrackVariantGroup.Member("cabin_b", 1))));
        assertFalse(BuilderRelaySubVariant.join(null, room, "cabin", "user_builds", null), "cabin is a group");
        TrackVariantGroupStore.injectForTesting(TrackKind.PORTAL_ROOM, "house",
            new TrackVariantGroup(List.of(new TrackVariantGroup.Member("evilhouse", 1))));
        assertFalse(BuilderRelaySubVariant.join(null, room, "attic", "evilhouse", null), "evilhouse is a member");
        assertFalse(BuilderRelaySubVariant.join(null, room, "evilhouse", "user_builds", null), "already under house");
        assertFalse(BuilderRelaySubVariant.join(null, room, "attic", "house", null),
            "a room-group write needs the world to relay the row out in");
    }

    @Test
    @DisplayName("a parent made for a load holds only its members: selfWeight 0, one member at default weight")
    void freshParentShape() {
        CarriageContentsGroup contents = CarriageContentsGroup.EMPTY
            .withSelfWeight(BuilderRelaySubVariant.NEW_PARENT_SELF_WEIGHT)
            .withMember(new CarriageContentsGroup.Member("cabin", CarriageContentsGroup.DEFAULT_WEIGHT));
        assertEquals(0, contents.selfWeight(), "the parent has no template of its own to stamp");
        assertEquals(List.of("cabin"), contents.members().stream().map(CarriageContentsGroup.Member::id).toList());
        assertEquals(CarriageContentsGroup.DEFAULT_WEIGHT, contents.members().get(0).weight());

        TrackVariantGroup rooms = TrackVariantGroup.EMPTY
            .withSelfWeight(BuilderRelaySubVariant.NEW_PARENT_SELF_WEIGHT)
            .withMember(new TrackVariantGroup.Member("attic", TrackVariantGroup.DEFAULT_WEIGHT));
        assertEquals(0, rooms.selfWeight());
        assertEquals("attic", rooms.members().get(0).id());
        assertEquals("user_builds", BuilderRelaySubVariant.DEFAULT_PARENT_ID);
    }
}
