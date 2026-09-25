package games.brennan.dungeontrain.template;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.worldgen.LapBand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pre-lap gate JSON ({@code TrainPhase} names) still reads, and writes back in {@link LapBand} names. */
final class LapBandGateMigrationTest {

    private static TemplateGate parse(String json) {
        return TemplateWeightCodec.parseGate(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    @DisplayName("an old phase name expands to every band it covered; new names read as themselves")
    void readsBothVocabularies() {
        assertEquals(EnumSet.of(LapBand.V_NETHER, LapBand.M_NETHER), parse("{\"phases\":[\"NETHER\"]}").phases());
        assertEquals(EnumSet.of(LapBand.M_NETHER, LapBand.L_LARGE_BIOMES, LapBand.L_AMPLIFIED, LapBand.L_BETA),
            parse("{\"phases\":[\"M_NETHER\",\"beta\"]}").phases());
        assertEquals(TemplateGate.ALL_PHASES, parse("{\"phases\":[\"NOT_A_BAND\"]}").phases());
        // Void is part of the End band now; an overworld stage that listed it stays off the End.
        assertEquals(LapBand.fromLegacy(games.brennan.dungeontrain.worldgen.TrainPhase.OVERWORLD),
            parse("{\"phases\":[\"OVERWORLD\",\"VOID\"]}").phases());
        // Old eras turn on their whole option; Void alone becomes the End.
        EnumSet<LapBand> eras = games.brennan.dungeontrain.worldgen.BandOption.PRE_FAR_LANDS.bands();
        eras.addAll(games.brennan.dungeontrain.worldgen.BandOption.OLD.bands());
        assertEquals(eras, parse("{\"phases\":[\"BETA\",\"ALPHA\"]}").phases());
        assertEquals(games.brennan.dungeontrain.worldgen.BandOption.END.bands(), parse("{\"phases\":[\"VOID\"]}").phases());
    }

    @Test
    @DisplayName("an old gate round-trips to the new vocabulary")
    void writesNewNames() {
        TemplateGate g = parse("{\"minLevel\":3,\"phases\":[\"NETHER\",\"UPSIDE_DOWN\"]}");
        JsonObject out = new JsonObject();
        TemplateWeightCodec.writeGateFields(out, g);
        assertEquals("[\"V_NETHER\",\"V_UPSIDE_DOWN\",\"V_REASSEMBLY\",\"M_NETHER\",\"M_SPHERES\"]",
            out.get("phases").toString());
        assertEquals(g, parse(out.toString()));
    }

    @Test
    @DisplayName("shipped data carries only LapBand names (scripts/worldgen/migrate-lap-bands.py)")
    void shippedDataMigrated() throws IOException {
        List<String> names = Stream.of(LapBand.values()).map(Enum::name).toList();
        Pattern array = Pattern.compile("\"phases\"\\s*:\\s*\\[([^\\]]*)\\]");
        Pattern item = Pattern.compile("\"([^\"]*)\"");
        try (Stream<Path> files = Files.walk(games.brennan.dungeontrain.RepoPaths.root().resolve("src/main/resources/data/dungeontrain"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                Matcher a = array.matcher(Files.readString(p));
                while (a.find()) {
                    Matcher i = item.matcher(a.group(1));
                    while (i.find()) assertTrue(names.contains(i.group(1)), p + ": " + i.group(1));
                }
            }
        }
    }
}
