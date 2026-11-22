package games.brennan.dungeontrain.template.dateperiod;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every {@code "periods"} id written into bundled weight / group files names a real date period. */
final class BundledPeriodIdsTest {

    @Test
    @DisplayName("Bundled templates only use known date period ids")
    void bundledIdsExist() throws IOException, java.net.URISyntaxException {
        List<String> unknown = new ArrayList<>();
        // The processed resources directory on the test classpath, so the test runs from any cwd.
        Path root = Path.of(BundledPeriodIdsTest.class.getResource("/data/dungeontrain/date_periods.json").toURI())
            .getParent();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : (Iterable<Path>) files.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.equals("weights.json") || n.endsWith(".group.json");
                })::iterator) {
                collect(JsonParser.parseString(Files.readString(f)), f, unknown);
            }
        }
        assertTrue(unknown.isEmpty(), "unknown period ids: " + unknown);
    }

    private static void collect(JsonElement el, Path file, List<String> unknown) {
        if (el.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                if (e.getKey().equals("periods") && e.getValue().isJsonArray()) {
                    for (JsonElement id : e.getValue().getAsJsonArray()) {
                        if (!DatePeriods.exists(id.getAsString())) unknown.add(file + ": " + id.getAsString());
                    }
                } else {
                    collect(e.getValue(), file, unknown);
                }
            }
        } else if (el.isJsonArray()) {
            for (JsonElement c : el.getAsJsonArray()) collect(c, file, unknown);
        }
    }
}
