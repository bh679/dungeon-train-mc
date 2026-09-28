package games.brennan.dungeontrain.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who may Cmd-click-delete a prefab from the creative menu: yours, or anything for the dev. */
final class PrefabDeletesTest {

    @Test
    @DisplayName("a prefab saved on this install is yours to delete, dev or not")
    void userTierIsAlwaysDeletable() {
        assertTrue(PrefabDeletes.decide(true, false, false));
        assertTrue(PrefabDeletes.decide(true, true, false));
    }

    @Test
    @DisplayName("bundled or imported prefabs are not yours outside dev mode")
    void othersAreNotDeletableWithoutDevMode() {
        assertFalse(PrefabDeletes.decide(false, false, false));
        assertFalse(PrefabDeletes.decide(false, false, true));
    }

    @Test
    @DisplayName("the dev may delete a prefab the source tree holds")
    void devDeletesSourceTreePrefabs() {
        assertTrue(PrefabDeletes.decide(false, true, true));
    }

    @Test
    @DisplayName("dev mode alone cannot delete a prefab only an imported package holds")
    void devCannotDeleteWhatItCannotReach() {
        assertFalse(PrefabDeletes.decide(false, true, false));
    }

    @Test
    @DisplayName("the wire ordinal of each kind stays put")
    void kindOrdinalsAreStable() {
        assertTrue(PrefabDeletes.Kind.VARIANT.ordinal() == 0 && PrefabDeletes.Kind.LOOT.ordinal() == 1);
    }
}
