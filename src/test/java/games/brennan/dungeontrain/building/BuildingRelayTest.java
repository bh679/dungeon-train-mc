package games.brennan.dungeontrain.building;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.BuilderSave;
import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.editor.relay.EditorRelayWrite;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.tools.BundledFingerprints;
import games.brennan.dungeontrain.train.CarriageBlockSnapshot;
import games.brennan.dungeontrain.train.CarriageSnapshotTemplate;
import net.minecraft.SharedConstants;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A building's trip to the relay and back.
 *
 * <p>The thing worth pinning is that the trip changes nothing a hash can see. An accepted building is
 * recognised on another install by the relay's hash of its blocks, and that install only has the file
 * the download wrote — so the file a save writes, the blob it uploads and the file a download writes
 * from that blob all have to come to the same hash.</p>
 */
class BuildingRelayTest {

    /** The largest blob the relay takes, as {@code BuilderRelayUpload} enforces it. */
    private static final int MAX_BLOCKS_CHARS = 690_000;

    private static final Path SHIPPED = games.brennan.dungeontrain.RepoPaths.resources().resolve("data/dungeontrain/structure/lost_city");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A shipped building as a save would write it: loaded, saved, and trimmed. */
    private static CompoundTag saved(String name) throws Exception {
        CompoundTag raw;
        try (InputStream in = Files.newInputStream(SHIPPED.resolve(name + ".nbt"))) {
            raw = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
        StructureTemplate template = new StructureTemplate();
        template.load(BuiltInRegistries.BLOCK.asLookup(), raw);
        return BuildingCapture.trimOuterAir(template.save(new CompoundTag()));
    }

    private static String blobOf(CompoundTag template) throws Exception {
        return CarriageBlockSnapshot.encode(CarriageSnapshotTemplate.fromTemplateTag(template));
    }

    @Test
    @DisplayName("a building is its own relay kind, named by its name alone")
    void namingIsItsOwnKind() {
        EditorRelayWrite.Naming naming = EditorRelayWrite.namingOf(new Template.Building("clock_tower"));
        assertEquals(BuilderPhotoPaths.Kind.BUILDING, naming.kind());
        assertEquals("", naming.subKind());
        assertEquals("clock_tower", naming.id());
        assertEquals("building", BuilderRelayKinds.idOf(naming.kind()));
        assertFalse(BuilderRelayKinds.canJoinTheTrain(naming.kind()), "no train slot holds a building");
        assertTrue(BuilderRelayKinds.canSubmitForReview(naming.kind()));
    }

    @Test
    @DisplayName("an official Lost City building has no relay name at all")
    void lostCityIsNeverNamed() {
        assertNull(EditorRelayWrite.namingOf(new Template.LostCity("skyscraper")));
        assertNull(BuilderRelayKinds.kindOf(BuilderRelayKinds.LOST_CITY));
    }

    @Test
    @DisplayName("what a building upload declares is the file's own size")
    void uploadDeclaresTheFilesSize() throws Exception {
        CompoundTag template = saved("civic_hall");
        BuilderSave.Written written = EditorRelayWrite.ofBuilding("civic_hall", template);
        assertNotNull(written);
        assertEquals(BuilderPhotoPaths.Kind.BUILDING, written.kind());
        assertEquals(BuildingSizes.sizeIn(template), written.size());
        CompoundTag snapshot = CarriageSnapshotTemplate.fromTemplateTag(template);
        assertEquals(new Vec3i(snapshot.getInt("l"), snapshot.getInt("h"), snapshot.getInt("w")), written.size());
        assertNull(EditorRelayWrite.ofBuilding("civic_hall", null));
    }

    @Test
    @DisplayName("a download writes a file with the hash the upload had — air and all")
    void roundTripKeepsTheHash() throws Exception {
        for (String name : List.of("civic_hall", "office_tower", "water_tower", "overpass")) {
            CompoundTag uploaded = saved(name);
            String blob = blobOf(uploaded);

            // The download: blob → snapshot → template → the file BuilderRelayInstall writes.
            StructureTemplate template = CarriageSnapshotTemplate.toTemplate(
                    CarriageBlockSnapshot.decode(blob), BuiltInRegistries.BLOCK.asLookup());
            CompoundTag downloaded = template.save(new CompoundTag());

            assertEquals(BuildingSizes.sizeIn(uploaded), BuildingSizes.sizeIn(downloaded), name);
            assertEquals(uploaded.getList("blocks", 10).size(), downloaded.getList("blocks", 10).size(),
                    name + ": every block comes back, the air inside included");
            assertEquals(blob, blobOf(downloaded), name + ": same blob, so same relay hash");
        }
    }

    @Test
    @DisplayName("every shipped building fits in one upload")
    void shippedBuildingsFitTheWire() throws Exception {
        try (var files = Files.list(SHIPPED)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".nbt")).sorted().toList()) {
                String name = file.getFileName().toString().replace(".nbt", "");
                int chars = blobOf(saved(name)).length();
                assertTrue(chars <= MAX_BLOCKS_CHARS, name + " uploads as " + chars + " chars");
            }
        }
    }

    @Test
    @DisplayName("the shipped buildings are fingerprinted, so an untouched copy is not counted as a build")
    void shippedBuildingsAreFingerprinted() throws Exception {
        List<BundledFingerprints.Fingerprint> all =
                BundledFingerprints.scan(games.brennan.dungeontrain.RepoPaths.resources().resolve("data/dungeontrain"));
        List<BundledFingerprints.Fingerprint> buildings =
                all.stream().filter(f -> "building".equals(f.kind())).toList();
        assertTrue(buildings.stream().anyMatch(f -> "civic_hall".equals(f.name())));
        assertTrue(buildings.size() >= 16, "all sixteen shipped buildings, found " + buildings.size());
        for (BundledFingerprints.Fingerprint f : buildings) assertEquals("", f.subKind());
    }
}
