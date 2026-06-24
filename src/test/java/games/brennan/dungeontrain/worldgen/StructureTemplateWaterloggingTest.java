package games.brennan.dungeontrain.worldgen;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards a worldgen structure-template invariant: <b>no DT structure stamped into generated
 * terrain may ship with a {@code waterlogged=true} block in its palette.</b>
 *
 * <p>The tunnel sections/portals and the pillar staircases are stamped via
 * {@code StructureTemplate.placeInWorld} with {@code LiquidSettings.IGNORE_WATERLOGGING}
 * (see {@code games.brennan.dungeontrain.tunnel.TunnelPlacer} and
 * {@code games.brennan.dungeontrain.track.TrackGenerator}) so they never inherit terrain
 * water — the overworld Nether transition band must stay bone-dry. That flag stops the
 * <em>existing</em> terrain fluid from flooding a placed waterloggable block, but it does
 * NOT strip a block whose own stored state is already {@code waterlogged=true}:
 * {@code setBlock} places that verbatim. So a template that was (re-)saved in the editor
 * while standing in water would still bake wet stairs into every world. This test closes
 * that second vector — the shipped templates themselves must be dry.</p>
 *
 * <p>If this fails, re-save the offending structure in the editor on dry land (or strip the
 * {@code waterlogged=true} palette entries from its {@code .nbt}).</p>
 */
final class StructureTemplateWaterloggingTest {

    private static final String DATA_REL = "src/main/resources/data/dungeontrain";

    /** Worldgen-stamped structure dirs whose stamps now use IGNORE_WATERLOGGING. */
    private static final List<String> STRUCTURE_DIRS = List.of(
        "tunnels/section",
        "tunnels/portal",
        "pillars/adjunct_stairs",
        "pillars/adjunct_stairs_entrance"
    );

    @Test
    void worldgenStructureTemplatesShipDry() throws IOException {
        Path dataDir = dataDir();
        List<String> offenders = new ArrayList<>();
        for (String rel : STRUCTURE_DIRS) {
            Path dir = dataDir.resolve(rel);
            if (!Files.isDirectory(dir)) continue;
            List<Path> templates;
            try (var s = Files.list(dir)) {
                templates = s.filter(f -> f.getFileName().toString().endsWith(".nbt")).sorted().toList();
            }
            for (Path p : templates) {
                for (String wetName : waterloggedPaletteNames(p)) {
                    offenders.add(rel + "/" + p.getFileName() + " -> " + wetName);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
            "Worldgen structure templates must ship with no waterlogged=true palette entries "
            + "(IGNORE_WATERLOGGING stops terrain water at placement time, but a pre-waterlogged "
            + "palette block is placed verbatim). Re-save these on dry land. Offenders: " + offenders);
    }

    /** Block names of palette entries that carry {@code waterlogged=true}, across both the
     *  single-palette and multi-palette (random-variant) structure-template forms. */
    private static List<String> waterloggedPaletteNames(Path path) throws IOException {
        CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        List<String> out = new ArrayList<>();
        collectWet(root.getList("palette", Tag.TAG_COMPOUND), out);          // single-palette form
        ListTag palettes = root.getList("palettes", Tag.TAG_LIST);           // multi-palette form
        for (int i = 0; i < palettes.size(); i++) {
            collectWet(palettes.getList(i), out);
        }
        return out;
    }

    private static void collectWet(ListTag palette, List<String> out) {
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag entry = palette.getCompound(i);
            if ("true".equals(entry.getCompound("Properties").getString("waterlogged"))) {
                out.add(entry.getString("Name"));
            }
        }
    }

    /** Walk up from the test working dir to locate the source data dir (cwd varies by runner). */
    private static Path dataDir() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(DATA_REL);
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException("data dir '" + DATA_REL + "' not found from user.dir="
            + System.getProperty("user.dir"));
    }
}
