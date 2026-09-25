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
        assertEquals(BandOption.CUSTOM.bands(), LapBand.resolve("ud"));
        assertEquals(BandOption.PRE_FAR_LANDS.bands(), LapBand.resolve("beta"), "a typed old era is its option");
        assertEquals(BandOption.END.bands(), LapBand.resolve("void"), "old Void on its own is the End");
        assertTrue(LapBand.resolve("nowhere").isEmpty());
        assertNull(LapBand.byToken("nether"));
    }

    @Test
    @DisplayName("the old values together cover every band; the direct matches are never empty")
    void legacyCoversEverything() {
        EnumSet<LapBand> all = EnumSet.noneOf(LapBand.class);
        for (TrainPhase p : TrainPhase.values()) {
            all.addAll(LapBand.fromLegacy(p));
            assertFalse(LapBand.directOf(p).isEmpty(), p.name());
        }
        assertEquals(EnumSet.allOf(LapBand.class), all);
    }

    @Test
    @DisplayName("migration: kinds fan out to every occurrence; the Legacy lap comes from Chuncks/Stacks/Spheres")
    void migrationTable() {
        assertEquals(BandOption.OVERWORLD.bands(), LapBand.fromLegacy(TrainPhase.OVERWORLD));
        assertEquals(BandOption.CUSTOM.bands(), LapBand.fromLegacy(TrainPhase.UPSIDE_DOWN));
        assertEquals(BandOption.CUSTOM.bands(), LapBand.fromLegacy(TrainPhase.SPHERES));
        assertEquals(BandOption.FAR_LANDS.bands(), LapBand.fromLegacy(TrainPhase.SKYLANDS));
        assertEquals(BandOption.OLD.bands(), LapBand.fromLegacy(TrainPhase.CLASSIC));
        assertTrue(LapBand.fromLegacy(TrainPhase.VOID).isEmpty(), "the End's void edges are part of the End now");
        // Every old value lands on whole options, never part of one.
        for (TrainPhase p : TrainPhase.values()) {
            int mask = LapBand.toMask(LapBand.fromLegacy(p));
            for (BandOption o : BandOption.values()) {
                assertTrue(o.state(mask) != BandOption.State.SOME, p + " splits " + o);
            }
        }
    }

    @Test
    @DisplayName("a whole old gate that migrates to nothing falls back to its direct matches, never to all bands")
    void migrateGateFallback() {
        assertEquals(BandOption.END.bands(), LapBand.migrateLegacyGate(EnumSet.of(TrainPhase.VOID)),
            "Void alone becomes the End");
        assertEquals(BandOption.NETHER.bands(), LapBand.migrateLegacyGate(EnumSet.of(TrainPhase.NETHER, TrainPhase.VOID)),
            "Void beside anything else adds nothing");
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
            assertEquals(new java.util.TreeSet<>(LapBand.fromLegacy(p).stream().map(Enum::name).toList()),
                new java.util.TreeSet<>(script.get(p.name())), p.name());
        }

        Matcher direct = Pattern.compile("DIRECT = \\{(.*?)\\n\\}", Pattern.DOTALL).matcher(py);
        assertTrue(direct.find());
        Matcher drow = Pattern.compile("\"([A-Z_]+)\": \\[(.*?)\\]", Pattern.DOTALL).matcher(direct.group(1));
        int rows = 0;
        while (drow.find()) {
            rows++;
            TrainPhase p = TrainPhase.valueOf(drow.group(1));
            assertEquals(new java.util.TreeSet<>(LapBand.directOf(p).stream().map(Enum::name).toList()),
                new java.util.TreeSet<>(quoted(drow.group(2))), p.name());
        }
        assertTrue(rows > 0);
    }

    private static List<String> quoted(String body) {
        Matcher m = Pattern.compile("\"([A-Z_0-9]+)\"").matcher(body);
        List<String> out = new java.util.ArrayList<>();
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
