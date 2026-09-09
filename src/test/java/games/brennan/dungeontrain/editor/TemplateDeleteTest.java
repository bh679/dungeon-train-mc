package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.track.variant.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a Remove destroys, and what it leaves alone.
 *
 * <p>Pinned because {@link TemplateDelete#plan} is the one place that decides which files go when a
 * template is removed from the X menu, and the two mistakes it guards against are opposite: leaving
 * a sidecar behind (a re-created template silently inherits it) and reaching into the source tree
 * for a built-in (a reset that erases shipped content).</p>
 *
 * <p>Path-based rather than through the stores, which resolve their roots via {@code FMLPaths} and
 * would need a Forge bootstrap — same posture as {@code PackageSaveOpsTest}.</p>
 */
final class TemplateDeleteTest {

    @TempDir Path tmp;

    private Path user(String subdir) { return tmp.resolve("user").resolve(subdir); }
    private Path src(String subdir) { return tmp.resolve("src").resolve(subdir); }

    private final Function<String, Path> configDirFor = this::user;
    private final Function<String, Path> sourceDirFor = this::src;

    private static List<Path> under(List<Path> files, Path root) {
        List<Path> out = new ArrayList<>();
        for (Path p : files) if (p.startsWith(root)) out.add(p);
        return out;
    }

    @Test
    @DisplayName("a custom carriage in dev mode: sidecars, twins, photo, nbt — sidecars before the nbt twin")
    void customCarriageInDevModeTakesEverything() {
        Path nbt = user("templates").resolve("cabin.nbt");
        Path sourceNbt = src("templates").resolve("cabin.nbt");
        TemplateDelete.Plan plan = TemplateDelete.plan(BuilderPhotoPaths.Kind.CARRIAGE, "", "cabin",
                nbt, sourceNbt, configDirFor, sourceDirFor, false, true);

        List<TemplateSidecars.Sidecar> sidecars = TemplateSidecars.filesFor(BuilderPhotoPaths.Kind.CARRIAGE, "", "cabin");
        assertEquals(4, sidecars.size(), "a carriage has four sidecars");
        List<Path> expected = new ArrayList<>();
        for (TemplateSidecars.Sidecar s : sidecars) expected.add(user(s.subdir()).resolve(s.basename()));
        for (TemplateSidecars.Sidecar s : sidecars) expected.add(src(s.subdir()).resolve(s.basename()));
        expected.add(user("templates").resolve("cabin.png"));
        expected.add(src("templates").resolve("cabin.png"));
        expected.add(nbt);
        expected.add(sourceNbt);
        assertEquals(expected, plan.files());

        assertTrue(plan.files().indexOf(sourceNbt) > plan.files().indexOf(src("templates").resolve("cabin.parts.json")),
                "the source .nbt twin goes after the source sidecars — the stores' own helpers gate on it existing");
        assertTrue(plan.unsetWeights());
        assertTrue(plan.unregister());
        assertFalse(plan.deleteOwnGroup(), "carriages have no group sidecar of their own");
        assertFalse(plan.rewriteMemberships());
        assertFalse(plan.forgetRoomSize());
    }

    @Test
    @DisplayName("a built-in carriage resets: config overlay only, nothing in src/, nothing in memory")
    void builtinCarriageIsAResetNotARemove() {
        Path nbt = user("templates").resolve("standard.nbt");
        Path sourceNbt = src("templates").resolve("standard.nbt");
        TemplateDelete.Plan plan = TemplateDelete.plan(BuilderPhotoPaths.Kind.CARRIAGE, "", "standard",
                nbt, sourceNbt, configDirFor, sourceDirFor, true, true);

        assertTrue(under(plan.files(), tmp.resolve("src")).isEmpty(), "a reset never reaches into the source tree");
        assertEquals(6, plan.files().size(), "4 sidecars + photo + nbt");
        assertTrue(plan.files().contains(nbt));
        assertTrue(plan.files().contains(user("templates").resolve("standard.png")));
        assertFalse(plan.unsetWeights(), "the shipped weight stays");
        assertFalse(plan.deleteOwnGroup());
        assertFalse(plan.rewriteMemberships());
        assertFalse(plan.unregister(), "the template still exists");
    }

    @Test
    @DisplayName("outside dev mode the source tree is never touched, even when one is there")
    void devModeOffKeepsTheSourceTree() {
        Path nbt = user("parts/floor").resolve("grate.nbt");
        Path sourceNbt = src("parts/floor").resolve("grate.nbt");
        TemplateDelete.Plan plan = TemplateDelete.plan(BuilderPhotoPaths.Kind.PART, "floor", "grate",
                nbt, sourceNbt, configDirFor, sourceDirFor, false, false);

        assertTrue(under(plan.files(), tmp.resolve("src")).isEmpty());
        assertTrue(plan.files().contains(user("parts/floor").resolve("grate.variants.json")));
        assertTrue(plan.files().contains(user("parts/floor").resolve("grate.png")));
        assertTrue(plan.unregister(), "no bundled fallback, so the registry entry goes");
        assertFalse(plan.unsetWeights(), "parts are weighted inside the carriage's .parts.json, not weights.json");
        assertFalse(plan.deleteOwnGroup());
    }

    @Test
    @DisplayName("a portal room carries its allow-list, copies and container document, and forgets its size")
    void portalRoomFilesAndSize() {
        Path nbt = user("portals/room").resolve("library.nbt");
        TemplateDelete.Plan plan = TemplateDelete.plan(BuilderPhotoPaths.Kind.PORTAL_ROOM, "", "library",
                nbt, null, configDirFor, sourceDirFor, false, true);

        List<String> names = plan.files().stream().map(p -> p.getFileName().toString()).toList();
        assertTrue(names.contains("library.variants.json"));
        assertTrue(names.contains("library.contents-allow.json"));
        assertTrue(names.contains("library.copies.json"));
        assertTrue(names.contains(ContainerContentsStore.basenameFor(
                ContainerContentsStore.trackPlotKey(TrackKind.PORTAL_ROOM, "library"))));
        assertTrue(plan.forgetRoomSize());
        assertTrue(plan.deleteOwnGroup());
        assertTrue(plan.rewriteMemberships());

        TemplateDelete.Plan tile = TemplateDelete.plan(BuilderPhotoPaths.Kind.TRACK, TrackKind.TILE.id(), "mossy",
                user("tracks/tile").resolve("mossy.nbt"), null, configDirFor, sourceDirFor, false, true);
        assertFalse(tile.forgetRoomSize(), "only rooms are measured");
        assertTrue(tile.unsetWeights());
    }

    @Test
    @DisplayName("a null source nbt means no source twins for it, even in dev mode")
    void noSourceNbtNoSourceTwinForIt() {
        Path nbt = user("contents").resolve("maze.nbt");
        TemplateDelete.Plan plan = TemplateDelete.plan(BuilderPhotoPaths.Kind.CONTENTS, "", "maze",
                nbt, null, configDirFor, sourceDirFor, false, true);
        assertFalse(plan.files().contains(src("contents").resolve("maze.nbt")));
        assertFalse(plan.files().contains(src("contents").resolve("maze.png")));
        assertTrue(plan.files().contains(src("contents").resolve("maze.variants.json")),
                "sidecar twins still go — they are resolved by directory, not by the nbt");
    }

    @Test
    @DisplayName("deleteAll: missing is silent, a refusal is a warning and the walk goes on")
    void deleteAllIsBestEffort() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("d"));
        Path a = Files.writeString(dir.resolve("a.json"), "{}");
        Path missing = dir.resolve("missing.json");
        Path stubborn = Files.createDirectories(dir.resolve("stubborn"));
        Files.writeString(stubborn.resolve("inside.txt"), "x");
        Path b = Files.writeString(dir.resolve("b.json"), "{}");

        List<String> warnings = new ArrayList<>();
        List<Path> removed = TemplateDelete.deleteAll(List.of(a, missing, stubborn, b), warnings);

        assertEquals(List.of(a, b), removed);
        assertEquals(1, warnings.size(), "the non-empty directory is the one refusal");
        assertTrue(warnings.get(0).contains("stubborn"));
        assertFalse(Files.exists(a));
        assertFalse(Files.exists(b), "a refusal earlier in the list does not stop the files after it");
        assertTrue(Files.isDirectory(stubborn));
    }
}
