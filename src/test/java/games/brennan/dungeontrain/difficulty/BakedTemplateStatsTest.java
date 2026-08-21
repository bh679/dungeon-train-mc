package games.brennan.dungeontrain.difficulty;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>No shipped template may bake an item with an impossible stat.</b>
 *
 * <p>This is the guard that was missing. A player looted a Train Dimension room chest holding a
 * diamond chestplate with +4,433,488.29 armour; the four items were sitting inside
 * {@code portals/room/evilhouse.nbt} as literal chest contents, having been rolled in the editor at
 * difficulty tier ≈44.3M and captured by {@code fillFromWorld} on save. Nothing rolled them at play
 * time, which is why every runtime code path read as correct. They shipped in #993 and this test
 * would have stopped that PR.</p>
 *
 * <p>It scans every {@code .nbt} in the data tree rather than a named list of directories: the bake
 * can happen to any template an author saves with a container in it, so an allowlist of "the ones we
 * thought of" is exactly the shape that let this through. Runs off the source tree via
 * {@link RepoPaths}, which throws rather than skipping when it cannot find the resources.</p>
 *
 * <p>If this fails: the offending template holds gear rolled against a broken difficulty frame.
 * {@code BakedItemStats} repairs it at read time so players are safe either way, but the data itself
 * should be fixed — strip the {@code minecraft:attribute_modifiers} component from the named items.</p>
 */
final class BakedTemplateStatsTest {

    private static final String DATA_REL = "src/main/resources/data/dungeontrain";

    @Test
    @DisplayName("no shipped template bakes an item with an impossible stat")
    void shippedTemplatesCarryNoImpossibleStats() throws IOException {
        Path dataDir = RepoPaths.root().resolve(DATA_REL);
        assertTrue(Files.isDirectory(dataDir), "data dir not found: " + dataDir);

        List<Path> templates;
        try (var s = Files.walk(dataDir)) {
            templates = s.filter(p -> p.getFileName().toString().endsWith(".nbt")).sorted().toList();
        }
        assertFalse(templates.isEmpty(), "found no .nbt templates to scan — the walk is broken");

        List<String> offenders = new ArrayList<>();
        for (Path p : templates) {
            CompoundTag root;
            try {
                root = NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap());
            } catch (IOException e) {
                offenders.add(dataDir.relativize(p) + " -> unreadable: " + e);
                continue;
            }
            for (String hit : absurdAmounts(root)) {
                offenders.add(dataDir.relativize(p) + " -> " + hit);
            }
        }
        assertTrue(offenders.isEmpty(),
            "Shipped templates must not bake items carrying attribute modifiers above "
                + BakedItemStats.MAX_SANE_MODIFIER + " — these were rolled against a broken "
                + "difficulty frame. Strip their minecraft:attribute_modifiers component. Offenders: "
                + offenders);
    }

    /** Every {@code id @ amount} in {@code tag} whose modifier is past the sane ceiling. */
    private static List<String> absurdAmounts(CompoundTag tag) {
        List<String> out = new ArrayList<>();
        collect(tag, out);
        return out;
    }

    private static void collect(CompoundTag tag, List<String> out) {
        if (BakedItemStats.isCorrupt(tag)) {
            ListTag modifiers = tag.getCompound("components")
                .getCompound("minecraft:attribute_modifiers")
                .getList("modifiers", Tag.TAG_COMPOUND);
            for (int i = 0; i < modifiers.size(); i++) {
                double amount = modifiers.getCompound(i).getDouble("amount");
                if (BakedItemStats.isAbsurd(amount)) {
                    out.add(tag.getString("id") + " @ " + String.format("%.2f", amount));
                }
            }
        }
        for (String key : tag.getAllKeys()) {
            Tag child = tag.get(key);
            if (child instanceof CompoundTag compound) {
                collect(compound, out);
            } else if (child instanceof ListTag list) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) instanceof CompoundTag element) collect(element, out);
                }
            }
        }
    }
}
