package games.brennan.dungeontrain.client.modcheck;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which jars Quit and Disable may rename, and what counts as disabled. Pure. */
class ModDisablerTest {

    private static final Path MODS = Path.of("/game/mods");

    @Test
    @DisplayName("A jar is disabled only when every mod in it is unsupported")
    void onlyWholeUnsupportedJars() {
        Map<Path, Set<String>> jars = new LinkedHashMap<>();
        jars.put(MODS.resolve("cool.jar"), Set.of("coolmod"));
        jars.put(MODS.resolve("bundle.jar"), Set.of("coolmod2", "sodium"));
        jars.put(MODS.resolve("pair.jar"), Set.of("a", "b"));
        assertEquals(List.of(MODS.resolve("cool.jar"), MODS.resolve("pair.jar")),
            ModDisabler.jarsToDisable(jars, Set.of("coolmod", "coolmod2", "a", "b")));
    }

    @Test
    @DisplayName("Only a plain .jar directly in the mods folder is ours to rename")
    void onlyJarsInModsFolder() {
        assertTrue(ModDisabler.isDisableable(MODS.resolve("cool.jar"), MODS));
        assertFalse(ModDisabler.isDisableable(Path.of("/game/libs/cool.jar"), MODS));
        assertFalse(ModDisabler.isDisableable(MODS.resolve("sub/cool.jar"), MODS));
        assertFalse(ModDisabler.isDisableable(MODS.resolve("build-classes"), MODS));
    }

    @Test
    @DisplayName("Nothing renamed means nothing disabled, whatever was skipped")
    void outcomeAnyDisabled() {
        assertFalse(new ModDisabler.Outcome(List.of(), List.of("coolmod")).anyDisabled());
        assertTrue(new ModDisabler.Outcome(List.of(MODS.resolve("cool.jar")), List.of("other")).anyDisabled());
    }
}
