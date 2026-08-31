package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.portal.PortalRoomSettings;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.template.TemplateMeta;
import games.brennan.dungeontrain.track.variant.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which files make up a template, and where they go.
 *
 * <p>Worth pinning because both ends of the relay round trip read this one answer and neither can
 * check the other: a wrong subdirectory writes a downloaded build's variants into a folder nothing
 * looks in, and a wrong basename files them under a name the store will never ask for. Either way
 * the build installs, opens, and is quietly missing everything the author set.</p>
 */
final class TemplateSidecarsTest {

    @Test
    @DisplayName("every kind is answered for — a kind added later fails here rather than silently carrying nothing")
    void coversEveryKind() {
        for (BuilderPhotoPaths.Kind kind : BuilderPhotoPaths.Kind.values()) {
            String subKind = switch (kind) {
                case PART -> "floor";
                case TRACK -> TrackKind.TILE.id();
                default -> "";
            };
            List<TemplateSidecars.Sidecar> files = TemplateSidecars.filesFor(kind, subKind, "brick_cabin");
            if (kind == BuilderPhotoPaths.Kind.CARRIAGE_GROUP) {
                assertTrue(files.isEmpty(), "a group is a list of ids and nothing else");
                assertFalse(TemplateSidecars.carries(kind));
                continue;
            }
            assertFalse(files.isEmpty(), kind + " has sidecars but names none");
            assertTrue(TemplateSidecars.carries(kind));
        }
    }

    @Test
    @DisplayName("roles are unique per kind, so a document's keys cannot collide")
    void rolesAreUnique() {
        for (BuilderPhotoPaths.Kind kind : BuilderPhotoPaths.Kind.values()) {
            String subKind = switch (kind) {
                case PART -> "floor";
                case TRACK -> TrackKind.TILE.id();
                default -> "";
            };
            List<String> roles = TemplateSidecars.filesFor(kind, subKind, "x").stream()
                    .map(TemplateSidecars.Sidecar::role).toList();
            assertEquals(roles.size(), roles.stream().distinct().count(), kind + " repeats a role");
        }
    }

    @Test
    @DisplayName("a portal room carries its variants, allow-list, copies and container links")
    void portalRoomFiles() {
        Map<String, TemplateSidecars.Sidecar> byRole = byRole(
                TemplateSidecars.filesFor(BuilderPhotoPaths.Kind.PORTAL_ROOM, "", "library"));

        assertEquals(sorted(java.util.Set.of("variants", "contents-allow", "copies", "containers")),
                sorted(byRole.keySet()));
        assertEquals("portals/room", byRole.get("variants").subdir(), "beside the room's own .nbt");
        assertEquals("library.variants.json", byRole.get("variants").basename());
        assertEquals("library.contents-allow.json", byRole.get("contents-allow").basename());
        assertEquals("library.copies.json", byRole.get("copies").basename());
        assertEquals("containers", byRole.get("containers").subdir());
    }

    @Test
    @DisplayName("a carriage carries its variants, part assignments, allow-list and container links")
    void carriageFiles() {
        Map<String, TemplateSidecars.Sidecar> byRole = byRole(
                TemplateSidecars.filesFor(BuilderPhotoPaths.Kind.CARRIAGE, "", "brick_cabin"));

        assertEquals("templates", byRole.get("variants").subdir());
        assertEquals("brick_cabin.variants.json", byRole.get("variants").basename());
        assertEquals("brick_cabin.parts.json", byRole.get("parts").basename());
        assertEquals("brick_cabin.contents-allow.json", byRole.get("contents-allow").basename());
    }

    @Test
    @DisplayName("a part's sidecar lives under its kind, because a part name is only unique within one")
    void partFilesAreScopedByKind() {
        TemplateSidecars.Sidecar variants = byRole(
                TemplateSidecars.filesFor(BuilderPhotoPaths.Kind.PART, "floor", "standard")).get("variants");

        assertEquals("parts/floor", variants.subdir());
        assertEquals("standard.variants.json", variants.basename());
    }

    @Test
    @DisplayName("a sub kind this install cannot resolve names nothing rather than guessing a directory")
    void unresolvableSubKindNamesNothing() {
        assertTrue(TemplateSidecars.filesFor(BuilderPhotoPaths.Kind.PART, "not_a_part_kind", "x").isEmpty());
        assertTrue(TemplateSidecars.filesFor(BuilderPhotoPaths.Kind.TRACK, "not_a_track_kind", "x").isEmpty());
    }

    @Test
    @DisplayName("paths follow the name a build lands under, not the one it was uploaded as")
    void pathsFollowTheInstalledName() {
        TemplateSidecars.Sidecar variants = byRole(
                TemplateSidecars.filesFor(BuilderPhotoPaths.Kind.PORTAL_ROOM, "", "library_copy")).get("variants");

        assertEquals("library_copy.variants.json", variants.basename(),
                "Load as new must file its sidecars beside its own .nbt");
    }

    @Test
    @DisplayName("a moved door survives the weights entry, which is the only thing carrying it")
    void doorPositionSurvivesTheWeightsEntry() {
        // The tag PortalRoomSettings.toTag() writes for a room whose two doorways are authored apart
        // — the long form, and the only shape that names the exit door at all.
        String moved = "bedrock_lock/exact/off/off/off/none/sealed/0/0/1/0";
        TemplateMeta authored = new TemplateMeta(3, TemplateGate.DEFAULT, "", moved);

        TemplateMeta back = TemplateSidecars.decodeWeights(
                TemplateSidecars.encodeWeights("library", authored));

        assertEquals(moved, back.mode(), "the door offsets ride in the mode tag or nowhere");
        assertEquals(3, back.weight());
        assertEquals(PortalRoomSettings.parse(moved), PortalRoomSettings.parse(back.mode()),
                "and parse back to the same settings the author saved");
        assertTrue(PortalRoomSettings.parse(back.mode()).doorsDiffer(),
                "the two doorways are still authored apart");
    }

    @Test
    @DisplayName("a room at its defaults still round-trips, and says nothing about a stage")
    void defaultsRoundTripWithoutAStage() {
        // The stage link travels as its own relay field. Carrying it here too would give an install
        // two sources for it that could disagree.
        TemplateMeta linked = new TemplateMeta(1, TemplateGate.DEFAULT, "desert", null);

        TemplateMeta back = TemplateSidecars.decodeWeights(
                TemplateSidecars.encodeWeights("plain", linked));

        assertEquals(1, back.weight());
        assertEquals("", back.stageId() == null ? "" : back.stageId());
    }

    @Test
    @DisplayName("a non-default gate rides along with the weight")
    void gateRoundTrips() {
        TemplateGate gate = new TemplateGate(3, 20, TemplateGate.ALL_PHASES);
        TemplateMeta back = TemplateSidecars.decodeWeights(
                TemplateSidecars.encodeWeights("gated", new TemplateMeta(4, gate, "", null)));

        assertEquals(3, back.gate().minLevel());
        assertEquals(20, back.gate().maxLevel());
    }

    @Test
    @DisplayName("nothing to apply is not a failure — it leaves this install's own sidecars alone")
    void applyIsANoOpWithoutADocument() {
        assertDoesNotThrow(() -> TemplateSidecars.apply(BuilderPhotoPaths.Kind.PORTAL_ROOM, "", "library", ""));
        assertDoesNotThrow(() -> TemplateSidecars.apply(BuilderPhotoPaths.Kind.PORTAL_ROOM, "", "library", null));
        assertDoesNotThrow(() -> TemplateSidecars.apply(BuilderPhotoPaths.Kind.PORTAL_ROOM, "", "library", "   "));
    }

    @Test
    @DisplayName("a document that will not parse costs the sidecars, never the install")
    void applySurvivesAMalformedDocument() {
        assertDoesNotThrow(() ->
                TemplateSidecars.apply(BuilderPhotoPaths.Kind.PORTAL_ROOM, "", "library", "{not json"));
    }

    private static Map<String, TemplateSidecars.Sidecar> byRole(List<TemplateSidecars.Sidecar> files) {
        return files.stream().collect(Collectors.toMap(TemplateSidecars.Sidecar::role, f -> f));
    }

    /** Sorted for a stable comparison, since the list's order is not part of the contract. */
    private static List<String> sorted(java.util.Set<String> raw) {
        return raw.stream().sorted().toList();
    }
}
