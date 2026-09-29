package games.brennan.dungeontrain.data;

import games.brennan.dungeonbackup.api.Registration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The bridge must work against whichever Dungeon Backup is on the classpath: register every merger
 * on 0.3.0+, register nothing (and not throw) on the 0.2.0 floor DT still requires.
 */
class RestoreMergeBridgeTest {

    @TempDir
    Path tmp;

    @Test
    void registersEveryMergerWhenTheLibraryHasTheHookAndNoneOtherwise() {
        Registration.Builder builder = Registration.builder("dungeontrain").dataRoot(tmp).rootLabel("dungeontrain");

        int registered = RestoreMergeBridge.registerAll(builder, "dungeontrain", RestoreMergers.BY_GLOB);

        assertEquals(RestoreMergeBridge.available() ? RestoreMergers.BY_GLOB.size() : 0, registered);
        builder.build(); // still a valid registration either way
    }
}
