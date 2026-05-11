package games.brennan.dungeontrain.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-store consistency check for the user-content migration.
 *
 * <p>The migration walks a hard-coded list of legacy subdirs; the per-store
 * {@code SUBDIR} constants are the source of truth for where each store
 * writes today. This test asserts the two lists stay in sync — if a future
 * change adds a new editor store under {@code dungeontrain/user/}, the
 * migration also needs to know about the legacy location, or upgrade-time
 * files for that kind silently fail to move.</p>
 *
 * <p>Reflective rather than direct so the test stays decoupled from each
 * store's internal naming. Touching {@link UserContentPaths#root()} or the
 * {@link UserContentExporter} directly would need a Forge
 * {@code FMLPaths.CONFIGDIR} bootstrap; in-game verification covers those.</p>
 */
final class UserContentMigrationTest {

    @Test
    @DisplayName("migration SUBDIRS lists the expected core slugs")
    void migrationCoversCoreSlugs() throws Exception {
        Set<String> migration = subdirsList();
        assertTrue(migration.contains("templates"), "templates");
        assertTrue(migration.contains("parts"), "parts");
        assertTrue(migration.contains("contents"), "contents");
        assertTrue(migration.contains("containers"), "containers");
        assertTrue(migration.contains("pillars"), "pillars");
        assertTrue(migration.contains("tunnels"), "tunnels");
        assertTrue(migration.contains("tracks"), "tracks");
        assertTrue(migration.contains("prefabs/loot"), "prefabs/loot");
        assertTrue(migration.contains("prefabs/block_variants"), "prefabs/block_variants");
    }

    @Test
    @DisplayName("migration SUBDIRS covers every editor store SUBDIR constant")
    void migrationCoversEveryEditorStoreSubdir() throws Exception {
        Set<String> migration = subdirsList();
        Set<String> declared = new HashSet<>();

        declared.add(readSubdir(CarriageTemplateStore.class));
        declared.add(readSubdir(CarriageVariantBlocks.class));
        declared.add(readSubdir(CarriageVariantPartsStore.class));
        declared.add(readSubdir(CarriageVariantContentsAllowStore.class));
        declared.add(readSubdir(CarriageContentsStore.class));
        declared.add(readSubdir(CarriageContentsVariantBlocks.class));
        declared.add(readSubdir(ContainerContentsStore.class));
        declared.add(readSubdir(LootPrefabStore.class));
        declared.add(readSubdir(BlockVariantPrefabStore.class));

        // SUBDIR_BASE for kind-nested stores: the migration walks the base
        // directory recursively, so the nested kind/<name> structure is
        // preserved automatically.
        declared.add(readField(CarriagePartTemplateStore.class, "SUBDIR_BASE"));
        declared.add(readField(CarriagePartVariantBlocks.class, "SUBDIR_BASE"));

        for (String slug : declared) {
            assertTrue(migration.contains(slug),
                "UserContentMigration.SUBDIRS missing slug '" + slug + "' declared by an editor store");
        }
    }

    private static String readSubdir(Class<?> storeClass) throws Exception {
        return readField(storeClass, "SUBDIR");
    }

    private static String readField(Class<?> storeClass, String name) throws Exception {
        Field f = storeClass.getDeclaredField(name);
        f.setAccessible(true);
        Object v = f.get(null);
        assertNotNull(v, storeClass.getSimpleName() + "." + name);
        return v.toString();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> subdirsList() throws Exception {
        Field f = UserContentMigration.class.getDeclaredField("SUBDIRS");
        f.setAccessible(true);
        Object value = f.get(null);
        assertEquals(List.class.isAssignableFrom(value.getClass()) ? List.class : null, List.class,
            "UserContentMigration.SUBDIRS should be a List<String>");
        return new HashSet<>((List<String>) value);
    }
}
