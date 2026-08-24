package games.brennan.dungeontrain.cheat;

import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the one invariant that makes
 * {@code RunIntegrity.isFreePlayApartFromCustomContent} useful: it must not consult
 * {@link EditorContentIntegrity}.
 *
 * <p>{@code EditorContentIntegrity.isSessionFreePlay()} is true whenever custom content is loading,
 * which is <em>always</em> at the call site — the join-time custom-content prompt. Folding it back
 * in (say, by "simplifying" the method to delegate to {@code isCheated} or
 * {@code isVisiblySessionFreePlay}) would make the predicate always true and the prompt would
 * silently never appear again. That is an invisible regression: nothing throws, no test about
 * prompting fails, players just stop being asked.</p>
 *
 * <p>Source-level because the predicate needs a {@code ServerPlayer} and a Forge bootstrap to
 * evaluate.</p>
 */
final class RunIntegrityCustomContentPredicateTest {

    private static final String METHOD = "isFreePlayApartFromCustomContent";

    @Test
    @DisplayName("the prompt-gating predicate ignores the custom-content taint itself")
    void predicateExcludesEditorContentIntegrity() throws IOException {
        String body = methodBody();

        assertFalse(body.contains("EditorContentIntegrity"),
            METHOD + "() consults EditorContentIntegrity, which is true whenever custom content is "
                + "loading — i.e. always, at the only call site. The custom-content join prompt "
                + "would never show again. Keep this as isCheated() minus that one term.");
        assertTrue(body.contains("isPermanentlyCheated"),
            METHOD + "() must still cover the sticky run taint — creative mode is the common case "
                + "that should skip the prompt.");
    }

    private static String methodBody() throws IOException {
        Path file = RepoPaths.root()
            .resolve("src/main/java/games/brennan/dungeontrain/cheat/RunIntegrity.java");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).contains("boolean " + METHOD + "(")) continue;
            StringBuilder body = new StringBuilder();
            for (int j = i; j < lines.size(); j++) {
                body.append(lines.get(j)).append('\n');
                if (lines.get(j).equals("    }")) return body.toString();
            }
        }
        return fail("No 'boolean " + METHOD + "(' declaration in " + file
            + " — the prompt-gating predicate was renamed or removed.");
    }
}
