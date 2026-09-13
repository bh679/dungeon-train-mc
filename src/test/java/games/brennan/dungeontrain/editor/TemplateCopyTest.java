package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link TemplateCopy}'s bundled fallback: a template that ships in the jar has its sidecars on the
 * classpath and nowhere in the config tree, so a copy that only looked on disk would carry nothing
 * from {@code library_dimension} — the room whose missing sky and walls prompted this.
 *
 * <p>Only the classpath branch is reachable here: the config-tree branch and the writes go through
 * {@code FMLPaths}, which these tests don't bootstrap. The role list itself is covered by
 * {@link TemplateSidecarsTest}.</p>
 */
final class TemplateCopyTest {

    private static TemplateSidecars.Sidecar role(List<TemplateSidecars.Sidecar> sidecars, String role) {
        return sidecars.stream().filter(s -> s.role().equals(role)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a bundled portal room's variants sidecar is found on the classpath")
    void bundledPortalRoomVariantsAreReadable() throws IOException {
        List<TemplateSidecars.Sidecar> sidecars =
            TemplateSidecars.filesFor(BuilderPhotoPaths.Kind.PORTAL_ROOM, null, "library_dimension");
        String text = TemplateCopy.readBundled(role(sidecars, "variants"));
        assertNotNull(text, "library_dimension.variants.json ships in the jar");
        assertFalse(text.isBlank());
    }

    @Test
    @DisplayName("a bundled carriage's parts and contents-allow sidecars are found on the classpath")
    void bundledCarriageSidecarsAreReadable() throws IOException {
        List<TemplateSidecars.Sidecar> sidecars =
            TemplateSidecars.filesFor(BuilderPhotoPaths.Kind.CARRIAGE, null, "standard");
        assertNotNull(TemplateCopy.readBundled(role(sidecars, "parts")));
        assertNotNull(TemplateCopy.readBundled(role(sidecars, "contents-allow")));
    }

    @Test
    @DisplayName("a role the template does not ship is null, not an error")
    void missingBundledRoleIsNull() throws IOException {
        List<TemplateSidecars.Sidecar> sidecars =
            TemplateSidecars.filesFor(BuilderPhotoPaths.Kind.PORTAL_ROOM, null, "no_such_room_xyz");
        for (TemplateSidecars.Sidecar sidecar : sidecars) {
            assertNull(TemplateCopy.readBundled(sidecar), sidecar.role());
        }
    }
}
