package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.tunnel.TunnelPlacer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure pieces of the Train Editor's Save-as: the guard, the file record, the baseline, the address. */
final class EditorSaveAsTest {

    @AfterEach
    void clearBaselines() {
        EditorSidecarBaseline.clearAll();
    }

    // ---- guard ----

    @Test
    @DisplayName("Only reloaded plots with unsaved edits block a Save-as — and never the source itself")
    void guardNamesUnsavedReloadedPlots() {
        Set<String> unsaved = Set.of("carriages|black", "carriages|cracked", "contents|black");
        List<String> blocking = EditorSaveAsGuard.unsavedAmong("carriages",
            List.of("black", "cracked", "fancywood"), "cracked", unsaved);
        assertEquals(List.of("black"), blocking);
    }

    @Test
    @DisplayName("A kind the dirty scan does not cover (parts) is never blocked")
    void guardIgnoresUnscannedKinds() {
        assertTrue(EditorSaveAsGuard.unsavedAmong(null, List.of("standard"), null,
            Set.of("carriages|standard")).isEmpty());
    }

    // ---- file record ----

    @Test
    @DisplayName("Restore puts a file back byte for byte, and deletes one that was not there")
    void filesRestoreExactly(@TempDir Path dir) throws IOException {
        Path present = dir.resolve("standard.nbt");
        Path absent = dir.resolve("sub/standard.variants.json");
        Files.writeString(present, "original", StandardCharsets.UTF_8);

        EditorSaveAsFiles recorded = EditorSaveAsFiles.capture(List.of(present, absent), p -> Optional.empty());
        Files.writeString(present, "edited", StandardCharsets.UTF_8);
        Files.createDirectories(absent.getParent());
        Files.writeString(absent, "edited sidecar", StandardCharsets.UTF_8);

        assertTrue(recorded.restore());
        assertEquals("original", Files.readString(present, StandardCharsets.UTF_8));
        assertFalse(Files.exists(absent));
    }

    @Test
    @DisplayName("A file with a recorded baseline is restored to the baseline, not to what was on disk at capture")
    void filesPreferTheBaseline(@TempDir Path dir) throws IOException {
        Path sidecar = dir.resolve("standard.variants.json");
        Files.writeString(sidecar, "before any edit", StandardCharsets.UTF_8);
        EditorSidecarBaseline.remember("carriages:standard", sidecar);
        Files.writeString(sidecar, "after a Z-menu edit", StandardCharsets.UTF_8);

        EditorSaveAsFiles recorded = EditorSaveAsFiles.capture(List.of(sidecar), EditorSidecarBaseline::lookup);
        Files.writeString(sidecar, "after the flush", StandardCharsets.UTF_8);

        assertTrue(recorded.restore());
        assertEquals("before any edit", Files.readString(sidecar, StandardCharsets.UTF_8));
    }

    // ---- baseline ----

    @Test
    @DisplayName("The first edit's view is the baseline; later edits do not overwrite it")
    void baselineFirstWriteWins(@TempDir Path dir) throws IOException {
        Path sidecar = dir.resolve("a.json");
        Files.writeString(sidecar, "v1", StandardCharsets.UTF_8);
        EditorSidecarBaseline.remember("k", sidecar);
        Files.writeString(sidecar, "v2", StandardCharsets.UTF_8);
        EditorSidecarBaseline.remember("k", sidecar);

        byte[] recorded = EditorSidecarBaseline.lookup(sidecar).orElseThrow().bytes();
        assertArrayEquals("v1".getBytes(StandardCharsets.UTF_8), recorded);
    }

    @Test
    @DisplayName("A stamp or save forgets the baseline — by key, or by file for a part")
    void baselineForgets(@TempDir Path dir) {
        Path keyed = dir.resolve("keyed.json");
        Path part = dir.resolve("part.json");
        EditorSidecarBaseline.remember("carriages:standard", keyed);
        EditorSidecarBaseline.remember(null, part);

        EditorSidecarBaseline.forget("carriages:standard");
        EditorSidecarBaseline.forgetFile(part);

        assertTrue(EditorSidecarBaseline.lookup(keyed).isEmpty());
        assertTrue(EditorSidecarBaseline.lookup(part).isEmpty());
    }

    @Test
    @DisplayName("A file that did not exist is recorded as absent")
    void baselineRecordsAbsence(@TempDir Path dir) {
        Path missing = dir.resolve("missing.json");
        EditorSidecarBaseline.remember("k", missing);
        assertFalse(EditorSidecarBaseline.lookup(missing).orElseThrow().existed());
    }

    // ---- address ----

    @Test
    @DisplayName("Every registry-free kind survives the round trip to the client and back")
    void addressRoundTrips() {
        List<Template> models = List.of(
            new Template.Part(CarriagePartKind.values()[0], "standard"),
            new Template.Track("nethertracks"),
            new Template.Pillar(PillarSection.TOP, "tuff"),
            new Template.Adjunct(PillarAdjunct.STAIRS, "default"),
            new Template.Tunnel(TunnelPlacer.TunnelVariant.PORTAL, "darktunnel"),
            new Template.PortalRoom("beam"));
        for (Template model : models) {
            EditorTemplateAddress address = EditorTemplateAddress.of(model);
            assertEquals(Optional.of(model), address.resolve(), address.toString());
        }
    }

    @Test
    @DisplayName("An address that names nothing resolves to nothing, rather than throwing")
    void addressRejectsNonsense() {
        assertTrue(new EditorTemplateAddress("pillar", "nope", "tuff").resolve().isEmpty());
        assertTrue(new EditorTemplateAddress("wat", "", "x").resolve().isEmpty());
        assertTrue(new EditorTemplateAddress("track", "", "").resolve().isEmpty());
    }
}
