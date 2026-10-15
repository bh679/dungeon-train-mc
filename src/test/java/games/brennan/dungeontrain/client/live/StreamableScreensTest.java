package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static games.brennan.dungeontrain.client.live.StreamableScreens.shown;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which menus a Live Eye stream may show: listed ones, and never while the player types into them. */
class StreamableScreensTest {

    private static final String SCREEN = "net.minecraft.client.gui.screens.Screen";
    private static final String CONTAINER = "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen";

    private static Set<String> line(String... names) {
        return Set.of(names);
    }

    @Test
    void inventoriesPauseAndAdvancementsAreShown() {
        assertTrue(shown(line("net.minecraft.client.gui.screens.inventory.InventoryScreen", CONTAINER, SCREEN), false));
        assertTrue(shown(line("some.mod.BackpackScreen", CONTAINER, SCREEN), false));
        assertTrue(shown(line("net.minecraft.client.gui.screens.PauseScreen", SCREEN), false));
        assertTrue(shown(line("net.minecraft.client.gui.screens.advancements.AdvancementsScreen", SCREEN), false));
        assertTrue(shown(line("betteradvancements.common.gui.BetterAdvancementsScreen", SCREEN), false));
    }

    @Test
    void typingScreensAndUnknownScreensAreNot() {
        assertFalse(shown(line("net.minecraft.client.gui.screens.ChatScreen", SCREEN), false));
        assertFalse(shown(line("net.minecraft.client.gui.screens.inventory.SignEditScreen", SCREEN), false));
        assertFalse(shown(line("net.minecraft.client.gui.screens.inventory.BookEditScreen", SCREEN), false));
        assertFalse(shown(line("net.minecraft.client.gui.screens.inventory.BookViewScreen", SCREEN), false));
        assertFalse(shown(line("net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen", SCREEN), false));
        assertFalse(shown(line("some.mod.AccountScreen", SCREEN), false));
    }

    @Test
    void theAnvilIsNotShownThoughItIsAContainer() {
        assertFalse(shown(line("net.minecraft.client.gui.screens.inventory.AnvilScreen",
            "net.minecraft.client.gui.screens.inventory.ItemCombinerScreen", CONTAINER, SCREEN), false));
    }

    @Test
    void photosAreShownButAlbumsAreNot() {
        String photo = "io.github.mortuusars.exposure.client.gui.screen.PhotographScreen";
        assertTrue(shown(line(photo, SCREEN), false));
        assertTrue(shown(line("io.github.mortuusars.exposure.client.gui.screen.album.ChildPhotographScreen", photo, SCREEN), false));
        assertFalse(shown(line("io.github.mortuusars.exposure.client.gui.screen.album.AlbumScreen", CONTAINER, SCREEN), false));
        assertFalse(shown(line("io.github.mortuusars.exposure.client.gui.screen.album.AlbumViewScreen", SCREEN), false));
    }

    @Test
    void aListedMenuIsNotShownWhileTyping() {
        assertFalse(shown(line(CONTAINER, SCREEN), true));
        assertFalse(shown(line("net.minecraft.client.gui.screens.PauseScreen", SCREEN), true));
    }

    @Test
    void lineageWalksSuperclasses() {
        Set<String> names = StreamableScreens.lineage(java.util.ArrayList.class);
        assertTrue(names.contains("java.util.AbstractList"));
        assertTrue(names.contains("java.lang.Object"));
        assertEquals("java.util.ArrayList", names.iterator().next());
    }
}
