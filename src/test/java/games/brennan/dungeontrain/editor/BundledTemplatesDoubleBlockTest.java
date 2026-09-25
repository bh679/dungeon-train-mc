package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.RepoPaths;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Every shipped template stamps its doors, beds and tall plants whole — whatever kind of template
 * it is.</b>
 *
 * <p>{@link DoubleBlockTemplateRepair} runs at load in every template store (carriages, contents,
 * parts, whole carriages, carriage groups, tracks). Its own test proves the rules on hand-built tags;
 * this one proves them on the real files, every {@code .nbt} in the data tree, so a template kind that
 * holds a half-captured door is caught whichever store it goes through. It also pins the specific
 * templates that shipped broken — a carriage ({@code fire}), contents ({@code twoway},
 * {@code statue}) and a part ({@code doors/copper}) — so the repair keeps reaching each kind.</p>
 *
 * <p>A partner cell holding some other block (a variant placeholder, a button) is left alone by
 * design — that cell is a variant or a deliberate build — so it is not counted as broken here.</p>
 */
final class BundledTemplatesDoubleBlockTest {

    private static final String DATA_REL = "src/main/resources/data/dungeontrain";

    private static final String[] EMPTY = {
        "minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:structure_void"};

    @Test
    @DisplayName("no shipped template of any kind stamps half a door, bed or tall plant")
    void everyTemplateKindStampsTwoSpaceBlocksWhole() throws IOException {
        Path dataDir = RepoPaths.root().resolve(DATA_REL);
        List<Path> templates = templatesUnder(dataDir);
        assertFalse(templates.isEmpty(), "found no .nbt templates to scan — the walk is broken");

        List<String> broken = new ArrayList<>();
        for (Path p : templates) {
            CompoundTag repaired = DoubleBlockTemplateRepair.repair(read(p)).tag();
            for (String hit : halfPlaced(repaired)) {
                broken.add(dataDir.relativize(p) + " -> " + hit);
            }
        }
        assertTrue(broken.isEmpty(),
            "Templates still hold half a two-space block after DoubleBlockTemplateRepair: " + broken);
    }

    @Test
    @DisplayName("the carriage, contents and part templates that shipped half doors come out whole")
    void knownBrokenTemplatesOfEachKindAreRepaired() throws IOException {
        Path dataDir = RepoPaths.root().resolve(DATA_REL);
        // carriage shell: two oxidized copper door lowers with nothing above them
        assertRepaired(dataDir.resolve("templates/fire.nbt"), 2, 0);
        // contents: a spruce lower under a mangrove upper
        assertRepaired(dataDir.resolve("contents/twoway.nbt"), 0, 1);
        // contents: tall-grass lowers (variant cells) with an empty space above
        assertRepaired(dataDir.resolve("contents/statue.nbt"), 22, 0);
        // part: an oxidized copper lower under a copper upper
        assertRepaired(dataDir.resolve("parts/doors/copper.nbt"), 0, 1);
    }

    private static void assertRepaired(Path file, int added, int rewritten) throws IOException {
        CompoundTag original = read(file);
        assertFalse(halfPlaced(original).isEmpty(), file + " no longer holds a half block — update this test");
        DoubleBlockTemplateRepair.Result r = DoubleBlockTemplateRepair.repair(original);
        assertEquals(added, r.added(), file + ": partner halves added");
        assertEquals(rewritten, r.rewritten(), file + ": mismatched halves rewritten");
        assertTrue(halfPlaced(r.tag()).isEmpty(), file + " still half-placed: " + halfPlaced(r.tag()));
    }

    /**
     * Every two-space half in {@code tag} whose partner cell (inside the bounds) is absent, empty, or
     * a different two-space block / the wrong side. Partners holding a non-two-space block are skipped.
     */
    private static List<String> halfPlaced(CompoundTag tag) {
        List<String> out = new ArrayList<>();
        if (!tag.contains("palette", Tag.TAG_LIST) || !tag.contains("blocks", Tag.TAG_LIST)) return out;
        ListTag palette = tag.getList("palette", Tag.TAG_COMPOUND);
        ListTag blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
        ListTag sizeTag = tag.getList("size", Tag.TAG_INT);
        int[] size = {sizeTag.getInt(0), sizeTag.getInt(1), sizeTag.getInt(2)};

        Map<List<Integer>, CompoundTag> cells = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag b = blocks.getCompound(i);
            cells.put(pos(b), palette.getCompound(b.getInt("state")));
        }
        for (Map.Entry<List<Integer>, CompoundTag> cell : cells.entrySet()) {
            CompoundTag state = cell.getValue();
            int[] off = DoubleBlockTemplateRepair.partnerOffset(state);
            if (off == null) continue;
            List<Integer> at = cell.getKey();
            List<Integer> partnerAt = List.of(at.get(0) + off[0], at.get(1) + off[1], at.get(2) + off[2]);
            if (!inside(partnerAt, size)) continue;
            CompoundTag partner = cells.get(partnerAt);
            String label = at + " " + state.getString("Name");
            if (partner == null || isEmpty(partner)) {
                out.add(label + " has no partner at " + partnerAt);
            } else if (DoubleBlockTemplateRepair.partnerOffset(partner) != null
                    && !partner.equals(DoubleBlockTemplateRepair.partnerState(state))) {
                // Compare against the full expected state only when the names agree; a lower half is
                // the authority, so a mismatch seen from the upper is reported once, from the lower.
                boolean sameBlock = partner.getString("Name").equals(state.getString("Name"));
                if (!sameBlock || !sameSideSwapped(state, partner)) {
                    out.add(label + " partner at " + partnerAt + " is " + partner.getString("Name"));
                }
            }
        }
        return out;
    }

    /** True when {@code partner} is the opposite half/part of {@code state}. */
    private static boolean sameSideSwapped(CompoundTag state, CompoundTag partner) {
        CompoundTag want = DoubleBlockTemplateRepair.partnerState(state).getCompound("Properties");
        CompoundTag have = partner.getCompound("Properties");
        return want.getString("half").equals(have.getString("half"))
            && want.getString("part").equals(have.getString("part"));
    }

    private static boolean isEmpty(CompoundTag state) {
        String name = state.getString("Name");
        for (String e : EMPTY) if (e.equals(name)) return true;
        return false;
    }

    private static List<Integer> pos(CompoundTag block) {
        ListTag p = block.getList("pos", Tag.TAG_INT);
        return List.of(p.getInt(0), p.getInt(1), p.getInt(2));
    }

    private static boolean inside(List<Integer> p, int[] size) {
        for (int i = 0; i < 3; i++) if (p.get(i) < 0 || p.get(i) >= size[i]) return false;
        return true;
    }

    private static List<Path> templatesUnder(Path dataDir) throws IOException {
        assertTrue(Files.isDirectory(dataDir), "data dir not found: " + dataDir);
        try (var s = Files.walk(dataDir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".nbt")).sorted().toList();
        }
    }

    private static CompoundTag read(Path p) throws IOException {
        return NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap());
    }
}
