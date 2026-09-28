package games.brennan.dungeontrain.builder.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every download outcome must have a line to show for it.
 *
 * <p>A download ends in exactly one {@link BuilderRelayDownload.Outcome}, and the screen turns that
 * into a translation key. A key with no entry renders as the raw key — the player is told
 * {@code gui.dungeontrain.builder.profile.download_timed_out} and nothing else — and nothing else
 * catches it: the switch is exhaustive over the enum, not over the lang file, so adding an outcome
 * compiles happily with no line behind it. That is precisely how this feature's own TIMED_OUT
 * arrived, so the check lives here rather than in a reviewer's head.</p>
 *
 * <p>Only {@code en_us} is asserted. The other twenty are the parity script's job
 * ({@code scripts/localization/check-lang-parity.py}, run in CI), and duplicating it here would
 * fail this test for a translation gap that is not what it is about.</p>
 */
class BuilderDownloadNoteKeysTest {

    @Test
    @DisplayName("every download outcome has an en_us line behind its note key")
    void everyOutcomeHasALine() throws IOException {
        Path lang = RepoPaths.resources().resolve("assets/dungeontrain/lang/en_us.json");
        JsonObject en = JsonParser.parseString(Files.readString(lang, StandardCharsets.UTF_8)).getAsJsonObject();

        List<String> missing = new ArrayList<>();
        for (BuilderRelayDownload.Outcome outcome : BuilderRelayDownload.Outcome.values()) {
            String key = noteKeyFor(outcome);
            if (key == null || !en.has(key)) missing.add(outcome + " -> " + key);
        }
        assertTrue(missing.isEmpty(), "outcomes with no en_us line: " + missing);
    }

    /**
     * The screen's mapping, reached without loading the screen.
     *
     * <p>{@code BuilderProfileScreen} is a client class and drags Minecraft's whole GUI stack in
     * with it, which a unit test cannot load — so the one method this test needs is called
     * reflectively. It is public and static precisely because two screens already share it.</p>
     */
    private static String noteKeyFor(BuilderRelayDownload.Outcome outcome) {
        try {
            Class<?> screen = Class.forName("games.brennan.dungeontrain.client.builder.BuilderProfileScreen");
            return (String) screen.getMethod("noteKeyFor", BuilderRelayDownload.Outcome.class)
                    .invoke(null, outcome);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new AssertionError("could not reach BuilderProfileScreen.noteKeyFor", e);
        }
    }
}
