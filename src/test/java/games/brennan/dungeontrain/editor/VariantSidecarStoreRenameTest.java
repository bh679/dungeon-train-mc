package games.brennan.dungeontrain.editor;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VariantSidecarStore#rename} is move-or-carry: a user-tier file is moved; a sidecar that
 * only exists in the session cache or the bundled resource is written under the new address; an
 * empty one with default mirror is nothing to carry. The post-write hook fires on every write.
 */
final class VariantSidecarStoreRenameTest {

    private static final String BUNDLED = "/data/dungeontrain/whole/room/basiccave1.variants.json";

    @TempDir
    Path user;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void clear() {
        VariantSidecarStore.clearCache();
    }

    /** A test-kind address rooted in the temp folder; {@code bundled} names a real classpath sidecar or nothing. */
    record TestAddress(Path root, String name, String bundled, MirrorDefaults defaults, AtomicInteger writes)
            implements VariantAddress {
        @Override public String cacheKey() { return "test:" + name; }
        @Override public String userSubdir() { return "test"; }
        @Override public String fileName() { return name + EXT; }
        @Override public Path userPath() { return root.resolve(fileName()); }
        @Override public Path findUserFile() { return Files.isRegularFile(userPath()) ? userPath() : null; }
        @Override public String bundledResource() { return bundled; }
        @Override public Path sourcePath() { return null; }
        @Override public MirrorDefaults defaultMirror() { return defaults; }
        @Override public void afterWrite() { writes.incrementAndGet(); }
    }

    private TestAddress address(String name, String bundled) {
        return new TestAddress(user, name, bundled, MirrorDefaults.NONE, new AtomicInteger());
    }

    private static List<VariantState> states() {
        return List.of(VariantState.of(Blocks.STONE.defaultBlockState()),
            VariantState.of(Blocks.COBBLESTONE.defaultBlockState()));
    }

    @Test
    @DisplayName("a user-tier file is moved, and the cache follows it")
    void movesUserFile() throws IOException {
        TestAddress from = address("a", "/nowhere");
        TestAddress to = address("b", "/nowhere");
        VariantDoc doc = VariantSidecarStore.loadFor(from, null);
        doc.put(new BlockPos(1, 1, 1), states());
        VariantSidecarStore.save(from, doc);
        assertTrue(Files.isRegularFile(from.userPath()));

        assertTrue(VariantSidecarStore.rename(from, to));
        assertFalse(Files.exists(from.userPath()));
        assertTrue(Files.isRegularFile(to.userPath()));
        assertNull(VariantSidecarStore.cached(from.cacheKey()));
        assertNotNull(VariantSidecarStore.cached(to.cacheKey()));
        assertEquals(1, VariantSidecarStore.loadFor(to, null).size());
        assertTrue(from.writes().get() >= 2 && to.writes().get() >= 1, "post-write hook fired on both sides");
    }

    @Test
    @DisplayName("a sidecar that only lives in the cache is written under the new address")
    void carriesCachedDocument() throws IOException {
        TestAddress from = address("cached", "/nowhere");
        TestAddress to = address("carried", "/nowhere");
        VariantDoc doc = VariantSidecarStore.loadFor(from, null);
        doc.put(new BlockPos(0, 0, 0), states());
        assertFalse(Files.exists(from.userPath()), "nothing saved yet — the document is cache-only");

        assertTrue(VariantSidecarStore.rename(from, to));
        assertTrue(Files.isRegularFile(to.userPath()));
        assertEquals(1, VariantSidecarStore.loadFor(to, null).size());
    }

    @Test
    @DisplayName("a bundled-only sidecar is written under the new address")
    void carriesBundledDocument() throws IOException {
        TestAddress from = address("bundled", BUNDLED);
        TestAddress to = address("copy", "/nowhere");
        assertTrue(VariantSidecarStore.loadFor(from, null).size() > 0, "fixture: the bundled room has variants");
        VariantSidecarStore.invalidate(from);

        assertTrue(VariantSidecarStore.rename(from, to));
        assertTrue(Files.isRegularFile(to.userPath()));
        assertEquals(VariantSidecarStore.loadFor(from, null).toJsonText(),
            Files.readString(to.userPath(), StandardCharsets.UTF_8), "the carry is the bundled document as the writer emits it");
    }

    @Test
    @DisplayName("nothing to carry: empty with default mirror")
    void emptyIsNotCarried() throws IOException {
        TestAddress from = address("empty", "/nowhere");
        TestAddress to = address("still-empty", "/nowhere");
        assertFalse(VariantSidecarStore.rename(from, to));
        assertFalse(Files.exists(to.userPath()));
    }

    @Test
    @DisplayName("a mirror-only sidecar is carried, and saving it blank deletes the file")
    void mirrorOnlyIsCarriedAndBlankSaveDeletes() throws IOException {
        TestAddress from = address("mirror", "/nowhere");
        TestAddress to = address("mirror2", "/nowhere");
        VariantDoc doc = VariantSidecarStore.loadFor(from, null);
        doc.setMirrorAxes(true, false, false);
        assertTrue(VariantSidecarStore.rename(from, to));
        VariantDoc carried = VariantSidecarStore.loadFor(to, null);
        assertTrue(carried.mirrorX());

        carried.setMirrorAxes(false, false, false);
        VariantSidecarStore.save(to, carried);
        assertFalse(Files.exists(to.userPath()), "empty + default mirror deletes the user-tier file");
    }
}
