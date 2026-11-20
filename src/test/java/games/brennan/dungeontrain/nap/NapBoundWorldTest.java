package games.brennan.dungeontrain.nap;

import games.brennan.dungeontrain.nap.NapBinding.Decision;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bound world holds a nap only once the player was saved there: a crash between the bind and the
 * join must not strand the nap ({@link NapBoundWorld}).
 */
final class NapBoundWorldTest {

    private static final String BOUND = "World 1700000000005";
    private static final String HERE = "World 1700000000006";
    private static final UUID OWNER = UUID.randomUUID();

    private static Path world(Path saves, CompoundTag data) throws IOException {
        Path dir = Files.createDirectories(saves.resolve(BOUND));
        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        NbtIo.writeCompressed(root, dir.resolve("level.dat"));
        return dir;
    }

    private static NapSnapshot boundNap() {
        return new NapSnapshot(UUID.randomUUID(), 1L, "0.1840.0", "World 1700000000000", new CompoundTag(),
            0.5, -149, "Overworld", -1, 312, false, "normal").boundTo(BOUND);
    }

    @Test
    @DisplayName("game died after the bind, before the join: the nap is free again")
    void noSavedPlayerIsFree(@TempDir Path saves) throws IOException {
        world(saves, new CompoundTag());
        boolean holds = NapBoundWorld.holdsLife(saves, BOUND, OWNER);
        assertFalse(holds);
        assertEquals(Decision.WAKE, NapBinding.decide(boundNap(), HERE, holds, false));
        assertTrue(NapBinding.waitsForNewWorld(boundNap(), holds), "title screen reads Reboard");
    }

    @Test
    @DisplayName("the singleplayer player saved in level.dat holds the nap there")
    void levelDatPlayerHolds(@TempDir Path saves) throws IOException {
        CompoundTag data = new CompoundTag();
        data.put("Player", new CompoundTag());
        world(saves, data);
        boolean holds = NapBoundWorld.holdsLife(saves, BOUND, OWNER);
        assertTrue(holds);
        assertEquals(Decision.IGNORE, NapBinding.decide(boundNap(), HERE, holds, false));
        assertFalse(NapBinding.waitsForNewWorld(boundNap(), holds));
    }

    @Test
    @DisplayName("a playerdata file for the owner holds the nap there")
    void playerDataHolds(@TempDir Path saves) throws IOException {
        Path dir = world(saves, new CompoundTag());
        Files.createDirectories(dir.resolve("playerdata"));
        Files.writeString(dir.resolve("playerdata").resolve(OWNER + ".dat"), "x");
        assertTrue(NapBoundWorld.holdsLife(saves, BOUND, OWNER));
    }

    @Test
    @DisplayName("a deleted world holds nothing")
    void missingWorld(@TempDir Path saves) {
        assertFalse(NapBoundWorld.holdsLife(saves, BOUND, OWNER));
    }

    @Test
    @DisplayName("an unreadable level.dat counts as holding: never risk a second copy")
    void corruptLevelDatHolds(@TempDir Path saves) throws IOException {
        Path dir = Files.createDirectories(saves.resolve(BOUND));
        Files.writeString(dir.resolve("level.dat"), "not nbt");
        assertTrue(NapBoundWorld.holdsLife(saves, BOUND, OWNER));
    }

    @Test
    @DisplayName("an id escaping the saves folder holds nothing")
    void pathEscape(@TempDir Path root) throws IOException {
        Path saves = Files.createDirectories(root.resolve("saves"));
        Path outside = Files.createDirectories(root.resolve("x"));
        CompoundTag rootTag = new CompoundTag();
        CompoundTag data = new CompoundTag();
        data.put("Player", new CompoundTag());
        rootTag.put("Data", data);
        NbtIo.writeCompressed(rootTag, outside.resolve("level.dat"));
        assertFalse(NapBoundWorld.holdsLife(saves, "../x", OWNER));
    }
}
