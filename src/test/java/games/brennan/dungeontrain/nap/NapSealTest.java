package games.brennan.dungeontrain.nap;

import games.brennan.dungeontrain.data.InstallKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link NapSeal}: nap files sealed under the install key, and what a broken or missing seal does. */
final class NapSealTest {

    private static final String SLOT = "survival-hard";

    @TempDir Path root;
    @TempDir Path keyDir;
    private NapStore store;
    private final UUID player = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        InstallKey.useDirectoryForTests(keyDir);
        store = new NapStore(root);
    }

    @AfterEach
    void tearDown() {
        InstallKey.resetForTests();
    }

    private static NapSnapshot survivalNap(String levelId) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("XpLevel", 12);
        return new NapSnapshot(UUID.randomUUID(), 50L, "0.1890.0", levelId, tag, 10.5, 3, "Overworld", 1, 7,
            false, "hard");
    }

    private CompoundTag rootOf(String slot) throws Exception {
        return NbtIo.readCompressed(store.fileFor(player, slot), NbtAccounter.unlimitedHeap());
    }

    /** Rewrites the file's sealed body with {@code edit} applied, keeping the old seal. */
    private void editBody(String slot, java.util.function.Consumer<CompoundTag> edit) throws Exception {
        CompoundTag root = rootOf(slot);
        CompoundTag body = NapSeal.fromBytes(root.getByteArray(NapSeal.TAG_BODY));
        edit.accept(body);
        root.putByteArray(NapSeal.TAG_BODY, NapSeal.toBytes(body));
        NbtIo.writeCompressed(root, store.fileFor(player, slot));
    }

    @Test
    @DisplayName("a saved nap is sealed and reads back unchanged and trusted")
    void sealedRoundTrip() throws Exception {
        NapSnapshot n = survivalNap("World 1");
        store.save(player, SLOT, n);

        assertTrue(NapSeal.isSealedFormat(rootOf(SLOT)));
        assertTrue(store.sealingStarted());
        NapStore.Loaded loaded = store.loadChecked(player, SLOT).orElseThrow();
        assertFalse(loaded.tampered());
        assertEquals(n, loaded.nap());
    }

    @Test
    @DisplayName("an edited nap still wakes, as Free Play")
    void editedBodyWakesAsFreePlay() throws Exception {
        store.save(player, SLOT, survivalNap("World 2"));
        editBody(SLOT, body -> body.getCompound("player").putInt("XpLevel", 999));

        NapStore.Loaded loaded = store.loadChecked(player, SLOT).orElseThrow();
        assertTrue(loaded.tampered());
        assertTrue(loaded.nap().cheated(), "woken as Free Play");
        assertTrue(store.hasFile(player, SLOT), "kept, not deleted");
    }

    @Test
    @DisplayName("a Free Play nap relabelled Survival is caught")
    void relabelledFreePlayCaught() throws Exception {
        NapSnapshot freePlay = survivalNap("World 3").asFreePlay();
        store.save(player, SLOT, freePlay);
        editBody(SLOT, body -> body.putBoolean("cheated", false));

        NapStore.Loaded loaded = store.loadChecked(player, SLOT).orElseThrow();
        assertTrue(loaded.tampered());
        assertTrue(loaded.nap().cheated());
    }

    @Test
    @DisplayName("a seal made for another slot, another player or another install does not match")
    void sealIsScoped(@TempDir Path otherKeyDir) throws Exception {
        CompoundTag body = survivalNap("World 4").toTag();
        CompoundTag sealed = NapSeal.wrap(body, NapSeal.scope(player, SLOT));

        assertEquals(NapSeal.Verdict.SEALED, NapSeal.open(sealed, NapSeal.scope(player, SLOT)).verdict());
        assertEquals(NapSeal.Verdict.TAMPERED, NapSeal.open(sealed, NapSeal.scope(player, "freeplay-hard")).verdict());
        assertEquals(NapSeal.Verdict.TAMPERED, NapSeal.open(sealed, NapSeal.scope(UUID.randomUUID(), SLOT)).verdict());
        InstallKey.useDirectoryForTests(otherKeyDir);
        assertEquals(NapSeal.Verdict.TAMPERED, NapSeal.open(sealed, NapSeal.scope(player, SLOT)).verdict());
    }

    @Test
    @DisplayName("a v2 file whose body doesn't read holds no nap")
    void unreadableBodyIsNoNap() throws Exception {
        CompoundTag sealed = NapSeal.wrap(survivalNap("World 5").toTag(), NapSeal.scope(player, SLOT));
        sealed.putByteArray(NapSeal.TAG_BODY, new byte[] {1, 2, 3});
        assertEquals(null, NapSeal.open(sealed, NapSeal.scope(player, SLOT)));
    }

    @Test
    @DisplayName("a stripped seal, once this install seals naps, wakes as Free Play")
    void strippedSealWakesAsFreePlay() throws Exception {
        NapSnapshot n = survivalNap("World 6");
        store.save(player, SLOT, n);
        NbtIo.writeCompressed(n.toTag(), store.fileFor(player, SLOT)); // the bare v1 tag

        NapStore.Loaded loaded = store.loadChecked(player, SLOT).orElseThrow();
        assertTrue(loaded.tampered());
        assertTrue(loaded.nap().cheated());
    }

    @Test
    @DisplayName("a waiting nap from before sealing is sealed in place and wakes as it was")
    void legacyNapAdopted() throws Exception {
        NapSnapshot n = survivalNap("World 7");
        NapTestFiles.writeUnmarked(store, player, SLOT, n);

        NapStore.Loaded loaded = store.loadChecked(player, SLOT).orElseThrow();
        assertFalse(loaded.tampered());
        assertEquals(n, loaded.nap());
        assertTrue(NapSeal.isSealedFormat(rootOf(SLOT)), "sealed in place");
        assertTrue(store.sealingStarted());
    }

    @Test
    @DisplayName("saving one nap first seals an older waiting nap in another slot")
    void saveAdoptsOtherSlots() throws Exception {
        NapSnapshot old = survivalNap("World 8");
        NapTestFiles.writeUnmarked(store, player, "survival-normal", old);
        store.save(player, SLOT, survivalNap("World 9"));

        NapStore.Loaded loaded = store.loadChecked(player, "survival-normal").orElseThrow();
        assertFalse(loaded.tampered());
        assertEquals(old, loaded.nap());
    }

    @Test
    @DisplayName("with nothing to seal, no marker and no install key are made, so a later restore is still adopted")
    void emptyStoreWritesNothing() throws Exception {
        Files.createDirectories(root.resolve(NapStore.DIR));
        assertTrue(store.adoptUnsealed());
        assertTrue(store.pendingLevelIds().isEmpty());
        assertFalse(store.sealingStarted());
        assertFalse(Files.exists(keyDir.resolve(".install-key")));

        NapSnapshot restored = survivalNap("World 10");
        NapTestFiles.writeUnmarked(store, player, SLOT, restored);
        NapStore.Loaded loaded = store.loadChecked(player, SLOT).orElseThrow();
        assertFalse(loaded.tampered());
        assertEquals(restored, loaded.nap());
    }

    @Test
    @DisplayName("binding reseals; a tampered nap stays Free Play once bound")
    void bindReseals() throws Exception {
        store.save(player, SLOT, survivalNap("World 11"));
        editBody(SLOT, body -> body.putInt("worldDepth", 9000));
        NapStore.Loaded tampered = store.loadChecked(player, SLOT).orElseThrow();
        assertTrue(tampered.tampered());

        store.bind(player, SLOT, tampered.nap(), "World 12");
        NapStore.Loaded bound = store.loadChecked(player, SLOT).orElseThrow();
        assertFalse(bound.tampered(), "resealed");
        assertTrue(bound.nap().cheated(), "but not laundered");
        assertEquals("World 12", bound.nap().wakeLevelId());
    }

    @Test
    @DisplayName("only sealed, properly named nap files can get a world removed")
    void pendingLevelIdsNeedsSeal() throws Exception {
        UUID other = UUID.randomUUID();
        store.save(player, SLOT, survivalNap("World 13"));
        store.save(other, SLOT, survivalNap("World 14"));
        assertEquals(java.util.Set.of("World 13", "World 14"), store.pendingLevelIds());

        Path dir = root.resolve(NapStore.DIR);
        NbtIo.writeCompressed(survivalNap("Precious World").toTag(), dir.resolve(UUID.randomUUID() + "-" + SLOT + ".dat"));
        NbtIo.writeCompressed(survivalNap("Precious World 2").toTag(), dir.resolve("not-a-nap.dat"));
        CompoundTag otherSeal = NapSeal.wrap(survivalNap("Precious World 3").toTag(), NapSeal.scope(other, SLOT));
        NbtIo.writeCompressed(otherSeal, dir.resolve(player + "-survival-easy.dat"));
        assertEquals(java.util.Set.of("World 13", "World 14"), store.pendingLevelIds());

        editBody(SLOT, body -> body.putString("levelId", "Precious World 4"));
        assertEquals(java.util.Set.of("World 14"), store.pendingLevelIds());
    }

    @Test
    @DisplayName("pending naps say which are tampered")
    void pendingCarriesTamper() throws Exception {
        store.save(player, SLOT, survivalNap("World 15"));
        editBody(SLOT, body -> body.putInt("carriage", 1));
        var pending = store.pending(player);
        assertEquals(1, pending.size());
        assertTrue(pending.get(0).tampered());
        assertNotNull(pending.get(0).nap());
    }

    @Test
    @DisplayName("a tampered nap is held to its slot's difficulty only")
    void slotCheckForTampered() {
        NapSnapshot freePlay = survivalNap("World 16").asFreePlay();
        assertFalse(NapSlot.matches(SLOT, freePlay));
        assertTrue(NapSlot.matches(SLOT, freePlay, false));
        assertFalse(NapSlot.matches("survival-easy", freePlay, false));
    }
}
