package games.brennan.dungeontrain.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each on-disk Free Play signal alone flips the probe; a clean survival save does not. */
class SaveFreePlayProbeTest {

    private static final UUID PLAYER = UUID.fromString("380df991-f603-344c-a090-369bad2a924a");

    private static void level(Path dir, boolean allowCommands) throws Exception {
        level(dir, allowCommands, null);
    }

    /** {@code embedded} is the singleplayer owner's copy inside {@code Data.Player}, or null for none. */
    private static void level(Path dir, boolean allowCommands, CompoundTag embedded) throws Exception {
        CompoundTag data = new CompoundTag();
        data.putBoolean(SaveFreePlayProbe.TAG_ALLOW_COMMANDS, allowCommands);
        if (embedded != null) data.put(SaveFreePlayProbe.TAG_PLAYER, embedded);
        CompoundTag root = new CompoundTag();
        root.put(SaveFreePlayProbe.TAG_DATA, data);
        NbtIo.writeCompressed(root, dir.resolve(SaveFreePlayProbe.LEVEL_DAT));
    }

    private static CompoundTag playerTag(boolean cheated, int gameType) {
        CompoundTag root = new CompoundTag();
        CompoundTag attachments = new CompoundTag();
        if (cheated) attachments.putBoolean(SaveFreePlayProbe.TAG_RUN_CHEATED, true);
        root.put(SaveFreePlayProbe.TAG_ATTACHMENTS, attachments);
        root.putInt(SaveFreePlayProbe.TAG_GAME_TYPE, gameType);
        return root;
    }

    private static void player(Path dir, boolean cheated, int gameType) throws Exception {
        CompoundTag root = playerTag(cheated, gameType);
        Path pd = dir.resolve(SaveFreePlayProbe.PLAYERDATA_DIR);
        Files.createDirectories(pd);
        NbtIo.writeCompressed(root, pd.resolve(PLAYER + ".dat"));
    }

    @Test
    void cleanSurvivalSaveIsNotFreePlay(@TempDir Path dir) throws Exception {
        level(dir, false);
        player(dir, false, 0);
        assertFalse(SaveFreePlayProbe.wasFreePlay(dir, PLAYER));
    }

    @Test
    void allowCommandsAloneIsFreePlay(@TempDir Path dir) throws Exception {
        level(dir, true);
        player(dir, false, 0);
        assertTrue(SaveFreePlayProbe.wasFreePlay(dir, PLAYER));
    }

    @Test
    void cheatedAttachmentAloneIsFreePlay(@TempDir Path dir) throws Exception {
        level(dir, false);
        player(dir, true, 0);
        assertTrue(SaveFreePlayProbe.wasFreePlay(dir, PLAYER));
    }

    @Test
    void creativeOrSpectatorModeIsFreePlay(@TempDir Path dir) throws Exception {
        level(dir, false);
        player(dir, false, SaveFreePlayProbe.GAME_TYPE_CREATIVE);
        assertTrue(SaveFreePlayProbe.wasFreePlay(dir, PLAYER));
        player(dir, false, SaveFreePlayProbe.GAME_TYPE_SPECTATOR);
        assertTrue(SaveFreePlayProbe.wasFreePlay(dir, PLAYER));
        player(dir, false, 2); // adventure is not a taint
        assertFalse(SaveFreePlayProbe.wasFreePlay(dir, PLAYER));
    }

    @Test
    void embeddedOwnerCopyInLevelDatCounts(@TempDir Path dir) throws Exception {
        // The singleplayer owner's copy inside level.dat is what Minecraft actually loads; a clean
        // playerdata/ file must not hide a tainted embedded one.
        level(dir, false, playerTag(false, SaveFreePlayProbe.GAME_TYPE_CREATIVE));
        player(dir, false, 0);
        assertTrue(SaveFreePlayProbe.wasFreePlay(dir, PLAYER));

        level(dir, false, playerTag(true, 0));
        assertTrue(SaveFreePlayProbe.wasFreePlay(dir, PLAYER));

        level(dir, false, playerTag(false, 0));
        assertFalse(SaveFreePlayProbe.wasFreePlay(dir, PLAYER));
    }

    @Test
    void missingFilesErrTowardsOffering(@TempDir Path dir) {
        assertFalse(SaveFreePlayProbe.wasFreePlay(dir, PLAYER));
    }
}
