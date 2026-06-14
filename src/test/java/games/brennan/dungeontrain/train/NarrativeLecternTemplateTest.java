package games.brennan.dungeontrain.train;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards a carriage-template invariant: <b>no {@code dungeontrain:narrative_lectern}
 * may ship with a book baked into its block-entity.</b> Narrative lecterns resolve
 * their book lazily on first right-click; a baked book pins them to one fixed story
 * forever (the "always pip" bug — campire/fireside were once saved with a resolved
 * pip book in the lectern BE).
 *
 * <p>If this fails, re-save the offending carriage in the editor with an EMPTY
 * lectern (or strip {@code Book}/{@code Page} from its block-entity NBT). The
 * runtime guard in {@code CarriageContentsPlacer.clearBakedNarrativeLecternBooks}
 * also strips such books at spawn, but the shipped data should be clean.</p>
 */
final class NarrativeLecternTemplateTest {

    private static final String REL = "src/main/resources/data/dungeontrain/contents";
    private static final String NARRATIVE_LECTERN = "dungeontrain:narrative_lectern";

    @Test
    void narrativeLecternTemplatesShipEmpty() throws IOException {
        Path contentsDir = contentsDir();
        List<Path> templates;
        try (var s = Files.list(contentsDir)) {
            templates = s.filter(f -> f.getFileName().toString().endsWith(".nbt")).sorted().toList();
        }
        List<String> offenders = new ArrayList<>();
        for (Path p : templates) {
            if (templateBakesLecternBook(p)) offenders.add(p.getFileName().toString());
        }
        assertTrue(offenders.isEmpty(),
            "narrative_lectern blocks must ship with no baked book (they resolve lazily on first "
            + "click). Re-save these carriages with an empty lectern. Offenders: " + offenders);
    }

    /** True if {@code path} has any narrative_lectern block whose block-entity carries a Book. */
    private static boolean templateBakesLecternBook(Path path) throws IOException {
        CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        Set<Integer> lecternStates = lecternPaletteIndices(root);
        if (lecternStates.isEmpty()) return false;
        ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag b = blocks.getCompound(i);
            if (lecternStates.contains(b.getInt("state")) && b.getCompound("nbt").contains("Book")) {
                return true;
            }
        }
        return false;
    }

    /** Single-palette indices whose block Name is the narrative lectern. */
    private static Set<Integer> lecternPaletteIndices(CompoundTag root) {
        Set<Integer> out = new HashSet<>();
        ListTag palette = root.getList("palette", Tag.TAG_COMPOUND);
        for (int i = 0; i < palette.size(); i++) {
            if (NARRATIVE_LECTERN.equals(palette.getCompound(i).getString("Name"))) out.add(i);
        }
        return out;
    }

    /** Walk up from the test working dir to locate the source contents dir (cwd varies by runner). */
    private static Path contentsDir() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(REL);
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException("contents dir '" + REL + "' not found from user.dir="
            + System.getProperty("user.dir"));
    }
}
