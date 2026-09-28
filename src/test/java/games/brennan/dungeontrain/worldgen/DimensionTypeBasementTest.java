package games.brennan.dungeontrain.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.portal.PortalRoomLayout;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.portal.PortalTwinLanes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the empty basement DT's dimension types keep under the world, which is where
 * {@link PortalTwinLanes} stamps portal twins.
 *
 * <p>Each DT overworld runs its {@code dimension_type} 80 blocks below its
 * {@code worldgen/noise_settings}. Terrain — and the bedrock layer {@code BedrockFloorEvents} stamps
 * at the noise floor — is unchanged by that; the space underneath is world no generation reaches and
 * no player can dig into. These are the invariants that space depends on, and the ones vanilla's
 * {@code DimensionType} codec rejects a world for violating.</p>
 */
final class DimensionTypeBasementTest {

    private static final String DIM_TYPES = "src/main/resources/data/dungeontrain/dimension_type";
    private static final String NOISE = "src/main/resources/data/dungeontrain/worldgen/noise_settings";

    /** Vanilla's ceiling on a dimension: {@code min_y + height} may not exceed this. */
    private static final int MAX_TOP = 2032;

    /** Every DT preset builds to the same sky ceiling; the basement is added below, not stolen above. */
    private static final int DT_SKY_TOP = 320;

    /** The default carriage size, which is what the built-in room is measured against. */
    private static final CarriageDims DEFAULT_DIMS = CarriageDims.DEFAULT;

    /**
     * Dimension types that are not overworld presets. A void world generates no terrain, so it has
     * no noise settings to sit below and no basement to stamp portal twins into — the three
     * basement invariants below are about a world it isn't.
     *
     * <p>The Train Builder's build platform is the only one today: {@code dungeon_train_builder}
     * pairs it with a {@code minecraft:flat} generator holding zero layers. It is still held to
     * {@link #dimensionTypesAreLegal()}, because vanilla's codec rejects an illegal dimension
     * whatever generates into it.</p>
     */
    private static final java.util.Set<String> VOID_DIMENSIONS = java.util.Set.of("builder.json");

    /**
     * Dimension types whose preset generates with {@code minecraft:flat} rather than noise. A flat
     * generator's floor is not in any noise settings file — {@code FlatLevelSource.getMinY()} is
     * hard-coded to {@code 0} — so its basement is simply {@code 0 - min_y}. These are held to every
     * basement invariant the noise presets are, with that floor standing in for the noise
     * {@code min_y}.
     *
     * <p>The Train Editor's world is the one today: void like the builder's, but with the plots at
     * y=230 and {@code /dt portal test} stamping into the basement, it needs the full DT shape.</p>
     */
    private static final java.util.Map<String, Integer> FLAT_FLOOR_DIMENSIONS =
        java.util.Map.of("editor.json", 0);

    private static final String PRESETS = "src/main/resources/data/dungeontrain/worldgen/world_preset";

    @Test
    @DisplayName("every DT dimension type is a legal one vanilla will load")
    void dimensionTypesAreLegal() throws IOException {
        for (Path f : dimensionTypes()) {
            JsonObject d = read(f);
            int minY = d.get("min_y").getAsInt();
            int height = d.get("height").getAsInt();
            String name = f.getFileName().toString();

            assertEquals(0, Math.floorMod(minY, 16), name + ": min_y must be a multiple of 16");
            assertEquals(0, Math.floorMod(height, 16), name + ": height must be a multiple of 16");
            assertTrue(minY >= -2032, name + ": min_y is below vanilla's limit");
            assertTrue(minY + height <= MAX_TOP, name + ": top is above vanilla's limit");
            assertTrue(d.get("logical_height").getAsInt() <= height,
                name + ": logical_height must fit inside the world");
            if (VOID_DIMENSIONS.contains(name)) {
                // No terrain to sit under, so no basement and no shared ceiling to hold it to.
                // It still may not poke out through vanilla's roof, asserted above.
                continue;
            }
            assertEquals(DT_SKY_TOP, minY + height,
                name + ": the basement goes below the world, it does not lower the sky");
        }
    }

    @Test
    @DisplayName("every preset's basement is deep enough for all six twin lanes")
    void basementHoldsEveryLane() throws IOException {
        // Measured at the built-in room's height, not at PortalRoomLayout.MAX_HEIGHT: lane spacing
        // follows the room now (PortalTwinLanes.laneHeight), so the six-lane guarantee is about the
        // room a world stamps when nothing taller has been authored. A world that does author a
        // taller room knowingly spends lanes on it.
        int builtIn = PortalRoomLayout.builtInSize(DEFAULT_DIMS).getY();

        for (Path f : overworldPresets()) {
            String name = f.getFileName().toString();
            int minY = read(f).get("min_y").getAsInt();
            int bedrockY = bedrockFor(f);

            assertTrue(bedrockY > minY,
                name + ": dimension type must run below its noise settings, or there is no basement");
            assertEquals(PortalTwinLanes.MAX_LANES,
                PortalTwinLanes.usableLanes(minY, bedrockY, builtIn),
                name + ": basement holds " + (bedrockY - minY) + " blocks, too few for every lane");

            int topLane = PortalTwinLanes.twinFloorY(minY, bedrockY,
                (PortalTwinLanes.MAX_LANES - 1) * 4, 4, builtIn);
            assertTrue(PortalTwinLanes.fitsUnderWorld(minY, bedrockY, topLane, builtIn),
                name + ": the highest lane would push a room up through the bedrock");
        }
    }

    @Test
    @DisplayName("every preset can stand up a room taller than the built-in one")
    void basementLeavesHeadroomToAuthorInto() throws IOException {
        int builtIn = PortalRoomLayout.builtInSize(DEFAULT_DIMS).getY();

        for (Path f : overworldPresets()) {
            String name = f.getFileName().toString();
            int minY = read(f).get("min_y").getAsInt();
            int bedrockY = bedrockFor(f);
            int tallest = PortalTwinLanes.maxStructureHeight(minY, bedrockY);

            assertTrue(tallest > builtIn,
                name + ": holds only " + tallest + " blocks, no taller than the built-in room");
            assertTrue(PortalTwinLanes.fitsUnderWorld(
                    minY, bedrockY, PortalTwinLanes.floorY(minY), tallest),
                name + ": its own tallest structure would reach the bedrock");
        }
    }

    @Test
    @DisplayName("terrain generation is untouched — noise settings still start at the bedrock")
    void noiseSettingsAreUnchanged() throws IOException {
        for (Path f : overworldPresets()) {
            if (FLAT_FLOOR_DIMENSIONS.containsKey(f.getFileName().toString())) {
                continue; // a flat generator has no noise settings to hold to the ceiling
            }
            JsonObject noise = read(noiseFor(f)).getAsJsonObject("noise");
            String name = f.getFileName().toString();
            // The noise region is what NoiseSettings.clampToHeightAccessor intersects with the level,
            // so a preset whose terrain reached into the basement would fill it with stone.
            assertEquals(DT_SKY_TOP, noise.get("min_y").getAsInt() + noise.get("height").getAsInt(),
                name + ": terrain must still build to the same ceiling");
        }
    }

    @Test
    @DisplayName("compat mode is untouched: it points at vanilla's dimension type")
    void compatPresetStillUsesVanilla() throws IOException {
        Path preset = repoFile("src/main/resources/data/dungeontrain/worldgen/world_preset/"
            + "dungeon_train_compat.json");
        String overworld = read(preset)
            .getAsJsonObject("dimensions")
            .getAsJsonObject("minecraft:overworld")
            .get("type").getAsString();
        assertEquals("minecraft:overworld", overworld,
            "Compatible Terrain mode must keep vanilla's dimension type, so terrain mods still apply");
    }

    @Test
    @DisplayName("the editor world is the builder recipe at full DT height: flat void, overworld only")
    void editorPresetIsFlatVoidOverworldOnly() throws IOException {
        JsonObject dims = read(repoFile(PRESETS + "/dungeon_train_editor.json")).getAsJsonObject("dimensions");
        assertEquals(java.util.Set.of("minecraft:overworld"), dims.keySet(),
            "the editor never leaves the overworld, so the nether and end are load time spent on nothing");
        JsonObject overworld = dims.getAsJsonObject("minecraft:overworld");
        assertEquals("dungeontrain:editor", overworld.get("type").getAsString(),
            "the editor world is identified by its own dimension type (EditorWorldLayout)");
        JsonObject generator = overworld.getAsJsonObject("generator");
        // FLAT_FLOOR_DIMENSIONS pins the editor's floor at 0 — that is only true of a flat generator.
        assertEquals("minecraft:flat", generator.get("type").getAsString(),
            "the basement maths above assume FlatLevelSource's floor of 0");
        assertEquals(0, generator.getAsJsonObject("settings").getAsJsonArray("layers").size(),
            "void: nothing exists in the editor world until the editor stamps it");
        // And the type it names must be the one the flat-floor table describes.
        JsonObject type = read(repoFile(DIM_TYPES + "/editor.json"));
        assertEquals(DT_SKY_TOP, type.get("min_y").getAsInt() + type.get("height").getAsInt(),
            "plots at EditorLayout.PLOT_Y plus an 80-tall room need the same 320 ceiling as every DT preset");
    }

    /**
     * Where terrain stops and the basement begins: the noise {@code min_y} for a noise preset, or
     * the flat generator's fixed floor for a {@link #FLAT_FLOOR_DIMENSIONS} entry.
     */
    private static int bedrockFor(Path dimensionType) throws IOException {
        Integer flatFloor = FLAT_FLOOR_DIMENSIONS.get(dimensionType.getFileName().toString());
        if (flatFloor != null) {
            return flatFloor;
        }
        return read(noiseFor(dimensionType)).getAsJsonObject("noise").get("min_y").getAsInt();
    }

    /** The noise settings a dimension type's preset pairs with — same file name in both folders. */
    private static Path noiseFor(Path dimensionType) {
        Path noise = repoFile(NOISE).resolve(dimensionType.getFileName());
        assertTrue(Files.isRegularFile(noise),
            "no noise settings alongside " + dimensionType.getFileName());
        return noise;
    }

    /**
     * The DT overworld presets — every dimension type except the void worlds, which have no
     * terrain and so none of the basement geometry the invariants here describe.
     */
    private static List<Path> overworldPresets() throws IOException {
        List<Path> all = dimensionTypes();
        List<Path> out = new ArrayList<>(all.size());
        List<String> voidsSeen = new ArrayList<>();
        List<String> flatsSeen = new ArrayList<>();
        for (Path p : all) {
            String name = p.getFileName().toString();
            if (VOID_DIMENSIONS.contains(name)) {
                voidsSeen.add(name);
            } else {
                if (FLAT_FLOOR_DIMENSIONS.containsKey(name)) flatsSeen.add(name);
                out.add(p);
            }
        }
        assertEquals(FLAT_FLOOR_DIMENSIONS.size(), flatsSeen.size(),
            "FLAT_FLOOR_DIMENSIONS names a dimension type that no longer exists: expected "
                + FLAT_FLOOR_DIMENSIONS.keySet() + ", found " + flatsSeen);
        // A rename would otherwise quietly re-admit a void world to the basement invariants, or
        // quietly exempt nothing at all. Neither should pass in silence.
        assertEquals(VOID_DIMENSIONS.size(), voidsSeen.size(),
            "VOID_DIMENSIONS names a dimension type that no longer exists: expected "
                + VOID_DIMENSIONS + ", found " + voidsSeen);
        assertFalse(out.isEmpty(), "no overworld presets found under " + DIM_TYPES);
        return out;
    }

    private static List<Path> dimensionTypes() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(repoFile(DIM_TYPES))) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(out::add);
        }
        assertFalse(out.isEmpty(), "no dimension types found under " + DIM_TYPES);
        return out;
    }

    private static JsonObject read(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    /** Walk up from the test working dir to locate a repo file (cwd varies by runner). */
    private static Path repoFile(String relative) {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("'" + relative + "' not found from user.dir="
            + System.getProperty("user.dir"));
    }
}
