package games.brennan.dungeontrain.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the shipped block-variant sidecars' player heads and other compass
 * ({@code ROTATION_16}) blocks:
 * <ul>
 *   <li>no player head is left at the default {@code rotation=0} without an
 *       authored compass rotation — shipped heads look into their room;</li>
 *   <li>every cell holding a compass block re-serialises to the JSON on disk,
 *       so no compass block carries a legacy 6-way {@code "dirs"} (which the
 *       parser drops) and an editor re-save gives no diff.</li>
 * </ul>
 */
final class ShippedHeadRotationTest {

    private static final String DATA_REL = "src/main/resources/data/dungeontrain";

    @Test
    @DisplayName("Shipped default-facing player heads all carry a compass rotation")
    void defaultHeadsAreAimed() throws IOException {
        List<String> unaimed = new ArrayList<>();
        forEachCell((file, pos, value, cell) -> {
            for (VariantState s : cell.states()) {
                BlockState st = s.state();
                if (st == null || !st.is(Blocks.PLAYER_HEAD)) continue;
                if (st.getValue(BlockStateProperties.ROTATION_16) != 0) continue;
                if (s.rotation().mode() != VariantRotation.Mode.OPTIONS) {
                    unaimed.add(file + " @ " + pos);
                }
            }
        });
        assertTrue(unaimed.isEmpty(), "player heads at rotation=0 without Options: " + unaimed);
    }

    @Test
    @DisplayName("Compass-block entries round-trip unchanged through the sidecar writer")
    void compassCellsRoundTrip() throws IOException {
        int[] checked = {0};
        forEachCell((file, pos, value, cell) -> {
            boolean hasCompass = cell.states().stream()
                .anyMatch(s -> s.state() != null && RotationApplier.isCompass(s.state()));
            if (!hasCompass) return;
            StringBuilder sb = new StringBuilder();
            CarriageVariantBlocks.appendCellJson(sb, cell.states(), cell.lockId(),
                cell.roll(), cell.scope(), cell.span());
            JsonArray onDisk = statesArray(value);
            JsonArray written = statesArray(JsonParser.parseString(sb.toString()));
            assertEquals(onDisk.size(), written.size(), file + " @ " + pos);
            // Only the compass entries — other legacy shapes (e.g. the old
            // command-block empty placeholder) migrate on re-save by design.
            for (int i = 0; i < cell.states().size(); i++) {
                BlockState st = cell.states().get(i).state();
                if (st == null || !RotationApplier.isCompass(st)) continue;
                assertEquals(onDisk.get(i), written.get(i), file + " @ " + pos + " #" + i);
                checked[0]++;
            }
        });
        assertTrue(checked[0] > 0, "expected at least one shipped compass-block entry");
    }

    private static JsonArray statesArray(JsonElement cellValue) {
        return cellValue.isJsonArray()
            ? cellValue.getAsJsonArray()
            : cellValue.getAsJsonObject().getAsJsonArray("states");
    }

    @FunctionalInterface
    private interface CellVisitor {
        void visit(String file, String pos, JsonElement value, CarriageVariantBlocks.ParsedCell cell);
    }

    private static void forEachCell(CellVisitor visitor) throws IOException {
        Path data = dataDir();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(data)) {
            files = walk.filter(p -> p.getFileName().toString().endsWith(".variants.json")).toList();
        }
        for (Path p : files) {
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            if (!root.has("variants") || !root.get("variants").isJsonObject()) continue;
            String file = data.relativize(p).toString();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("variants").entrySet()) {
                BlockPos pos = CarriageVariantBlocks.parsePos(e.getKey());
                CarriageVariantBlocks.ParsedCell cell = CarriageVariantBlocks.parseCellValue(
                    e.getValue(), BuiltInRegistries.BLOCK.asLookup(), file, pos);
                if (cell == null) continue;
                visitor.visit(file, e.getKey(), e.getValue(), cell);
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
