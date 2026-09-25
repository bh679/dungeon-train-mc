package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-lap band vocabulary: laps, letters, masks, tokens and the pre-lap migration table. */
final class LapBandTest {

    private static String letters(LapBand.Lap lap) {
        return lap.members().stream().map(LapBand::letter).collect(Collectors.joining());
    }

    @Test
    @DisplayName("each lap lists its bands in cycle order")
    void lapLetters() {
        assertEquals("ONOEUR", letters(LapBand.Lap.VANILLA));
        assertEquals("ONOESO", letters(LapBand.Lap.MOD));
        assertEquals("LABFCSFAICSV", letters(LapBand.Lap.LEGACY));
        assertEquals("OCOS", letters(LapBand.Lap.CORRUPT));
        assertEquals("VMLC", Stream.of(LapBand.Lap.values()).map(LapBand.Lap::letter).collect(Collectors.joining()));
    }

    @Test
    @DisplayName("lap masks partition ALL_MASK, which still fits an int")
    void masks() {
        assertTrue(LapBand.values().length <= 31);
        int union = 0;
        for (LapBand.Lap lap : LapBand.Lap.values()) {
            assertEquals(0, union & lap.mask());
            union |= lap.mask();
        }
        assertEquals(LapBand.ALL_MASK, union);
        EnumSet<LapBand> set = EnumSet.of(LapBand.M_NETHER, LapBand.C_STACKS);
        assertEquals(set, LapBand.fromMask(LapBand.toMask(set)));
    }

    @Test
    @DisplayName("resolve: a band token is itself; an old phase token or alias expands; unknown is empty")
    void resolve() {
        assertEquals(EnumSet.of(LapBand.M_NETHER), LapBand.resolve("m_nether"));
        assertEquals(EnumSet.of(LapBand.M_NETHER), LapBand.resolve("M_NETHER"));
        assertEquals(EnumSet.of(LapBand.V_NETHER, LapBand.M_NETHER), LapBand.resolve("NETHER"));
        assertEquals(EnumSet.of(LapBand.V_UPSIDE_DOWN), LapBand.resolve("ud"));
        assertTrue(LapBand.resolve("nowhere").isEmpty());
        assertNull(LapBand.byToken("nether"));
    }

    @Test
    @DisplayName("every old phase expands to at least one band, and together they cover every band")
    void legacyCoversEverything() {
        EnumSet<LapBand> all = EnumSet.noneOf(LapBand.class);
        for (TrainPhase p : TrainPhase.values()) {
            EnumSet<LapBand> bands = LapBand.fromLegacy(p);
            assertFalse(bands.isEmpty(), p.name());
            all.addAll(bands);
        }
        assertEquals(EnumSet.allOf(LapBand.class), all);
    }

    @Test
    @DisplayName("the data migration script's tables match LapBand")
    void migrationScriptInStep() throws IOException {
        String py = Files.readString(games.brennan.dungeontrain.RepoPaths.root().resolve("scripts/worldgen/migrate-lap-bands.py"));
        Matcher order = Pattern.compile("ORDER = \\[(.*?)\\]", Pattern.DOTALL).matcher(py);
        assertTrue(order.find());
        assertEquals(Stream.of(LapBand.values()).map(Enum::name).toList(), quoted(order.group(1)));

        Matcher table = Pattern.compile("FROM_LEGACY = \\{(.*?)\\n\\}", Pattern.DOTALL).matcher(py);
        assertTrue(table.find());
        Matcher row = Pattern.compile("\"([A-Z_]+)\": \\[(.*?)\\]", Pattern.DOTALL).matcher(table.group(1));
        Map<String, List<String>> script = new LinkedHashMap<>();
        while (row.find()) script.put(row.group(1), quoted(row.group(2)));
        assertEquals(TrainPhase.values().length, script.size());
        for (TrainPhase p : TrainPhase.values()) {
            assertEquals(LapBand.fromLegacy(p).stream().map(Enum::name).toList(), script.get(p.name()), p.name());
        }
    }

    private static List<String> quoted(String body) {
        Matcher m = Pattern.compile("\"([A-Z_0-9]+)\"").matcher(body);
        List<String> out = new java.util.ArrayList<>();
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
