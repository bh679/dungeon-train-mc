package games.brennan.dungeontrain.player;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnderChestExpansionStoreTest {

    @TempDir
    Path dir;

    @Test
    void unknownPlayerIsNotExpanded() {
        EnderChestExpansionStore store = new EnderChestExpansionStore(dir);
        assertFalse(store.isExpanded(UUID.randomUUID()));
    }

    @Test
    void markThenReadRoundTrips() {
        EnderChestExpansionStore store = new EnderChestExpansionStore(dir);
        UUID uuid = UUID.randomUUID();
        assertTrue(store.markExpanded(uuid));
        assertTrue(store.isExpanded(uuid));
        assertTrue(Files.isRegularFile(store.file(uuid)));
        // A fresh instance over the same folder sees it — nothing is held only in memory.
        assertTrue(new EnderChestExpansionStore(dir).isExpanded(uuid));
    }

    @Test
    void flagIsPerPlayer() {
        EnderChestExpansionStore store = new EnderChestExpansionStore(dir);
        UUID a = UUID.randomUUID();
        store.markExpanded(a);
        assertFalse(store.isExpanded(UUID.randomUUID()));
    }

    @Test
    void unreadableFileReadsAsNotExpanded() throws IOException {
        EnderChestExpansionStore store = new EnderChestExpansionStore(dir);
        UUID uuid = UUID.randomUUID();
        Files.createDirectories(dir);
        Files.writeString(store.file(uuid), "not nbt");
        assertFalse(store.isExpanded(uuid));
    }
}
