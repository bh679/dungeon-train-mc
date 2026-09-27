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

/** Which jars Quit and Disable may rename, and the Windows after-exit script. Pure. */
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
    @DisplayName("The Windows script renames to .jar.disabled, retries, and deletes itself")
    void windowsScript() {
        String s = ModDisabler.windowsScript(List.of(Path.of("C:\\game\\mods\\cool mod.jar")));
        assertTrue(s.contains("ren \"C:\\game\\mods\\cool mod.jar\" \"cool mod.jar.disabled\""), s);
        assertTrue(s.contains("timeout /t 1"), s);
        assertTrue(s.contains("del \"%~f0\""), s);
    }
}
