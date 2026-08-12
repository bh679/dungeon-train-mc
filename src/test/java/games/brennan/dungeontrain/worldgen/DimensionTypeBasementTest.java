package games.brennan.dungeontrain.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.portal.PortalRoomLayout;
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
            assertEquals(DT_SKY_TOP, minY + height,
                name + ": the basement goes below the world, it does not lower the sky");
        }
    }

    @Test
    @DisplayName("every preset's basement is deep enough for all six twin lanes")
    void basementHoldsEveryLane() throws IOException {
        for (Path f : dimensionTypes()) {
            String name = f.getFileName().toString();
            int minY = read(f).get("min_y").getAsInt();
            int bedrockY = read(noiseFor(f)).getAsJsonObject("noise").get("min_y").getAsInt();

            assertTrue(bedrockY > minY,
                name + ": dimension type must run below its noise settings, or there is no basement");
            assertEquals(PortalTwinLanes.MAX_LANES, PortalTwinLanes.usableLanes(minY, bedrockY),
                name + ": basement holds " + (bedrockY - minY) + " blocks, too few for every lane");

            int topLane = PortalTwinLanes.twinFloorY(minY, bedrockY,
                (PortalTwinLanes.MAX_LANES - 1) * 4, 4);
            assertTrue(PortalTwinLanes.fitsUnderWorld(
                    minY, bedrockY, topLane, PortalRoomLayout.MAX_HEIGHT),
                name + ": the highest lane would push a room up through the bedrock");
        }
    }

    @Test
    @DisplayName("terrain generation is untouched — noise settings still start at the bedrock")
    void noiseSettingsAreUnchanged() throws IOException {
        for (Path f : dimensionTypes()) {
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

    /** The noise settings a dimension type's preset pairs with — same file name in both folders. */
    private static Path noiseFor(Path dimensionType) {
        Path noise = repoFile(NOISE).resolve(dimensionType.getFileName());
        assertTrue(Files.isRegularFile(noise),
            "no noise settings alongside " + dimensionType.getFileName());
        return noise;
    }

    /**
     * The DT overworld presets — every dimension type paired with a noise settings file of the same
     * name.
     *
     * <p>Pairing rather than listing the folder, because not everything in it is a stretch of the
     * train's world. {@code builder.json} is the Train Builder's workshop: a fixed-time flat world
     * with no noise settings, no terrain, and no portals, so every invariant below — a basement
     * under the bedrock, six twin lanes in it, terrain that stops at the same ceiling — is a
     * question that does not apply to it. Asserting them anyway would fail the build for a file that
     * is behaving correctly.</p>
     *
     * <p>The pairing is the test, not a way around it: a real overworld preset that lost its noise
     * settings would silently drop out of this list, so {@link #everyOverworldPresetIsChecked}
     * pins the count.</p>
     */
    private static List<Path> dimensionTypes() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(repoFile(DIM_TYPES))) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                .filter(p -> Files.isRegularFile(repoFile(NOISE).resolve(p.getFileName())))
                .forEach(out::add);
        }
        assertFalse(out.isEmpty(), "no dimension types found under " + DIM_TYPES);
        return out;
    }

    @Test
    @DisplayName("every noise settings preset has a dimension type checked against it")
    void everyOverworldPresetIsChecked() throws IOException {
        // The backstop on the pairing above: an overworld preset whose dimension type went missing —
        // or was renamed out of step with its noise settings — would leave the basement invariants
        // untested for that stretch of the world rather than failing.
        List<Path> noiseFiles = new ArrayList<>();
        try (Stream<Path> files = Files.list(repoFile(NOISE))) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(noiseFiles::add);
        }
        assertEquals(noiseFiles.size(), dimensionTypes().size(),
            "every noise settings file must have a dimension type of the same name beside it");
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
