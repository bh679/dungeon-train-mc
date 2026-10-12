package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveStreamSettingsButtonTest {

    @Test
    void sitsUnderThePreviewFlushRightAtEverySize() {
        for (int w : new int[] {80, 120, 160, 240}) {
            LiveHeadViewer.Box preview = LiveHeadViewer.layout(480, w);
            LiveHeadViewer.Box b = LiveStreamSettingsButton.layout(preview);
            assertEquals(preview.x() + preview.w(), b.x() + b.w(), "flush with the preview's right edge at " + w);
            assertEquals(preview.y() + preview.h() + LiveHeadViewer.BORDER + LiveStreamSettingsButton.GAP, b.y(),
                "just under the preview's bottom border at " + w);
        }
    }

    @Test
    void isASquareIconButtonWithTheCogCentred() {
        LiveHeadViewer.Box b = LiveStreamSettingsButton.layout(LiveHeadViewer.layout(480, 80));
        assertEquals(LiveStreamSettingsButton.SIZE, b.w());
        assertEquals(LiveStreamSettingsButton.SIZE, b.h());
        assertEquals(0, (LiveStreamSettingsButton.SIZE - LiveStreamSettingsButton.ICON) % 2, "the cog sits dead centre");
    }

    @Test
    void doesNotOverlapThePreviewsClickArea() {
        LiveHeadViewer.Box preview = LiveHeadViewer.layout(480, 160);
        LiveHeadViewer.Box b = LiveStreamSettingsButton.layout(preview);
        assertFalse(LiveHeadViewer.contains(preview, b.x() + 1, b.y()), "the resize click and the button never share a pixel");
        assertTrue(LiveStreamSettingsButton.contains(b, b.x(), b.y()));
        assertFalse(LiveStreamSettingsButton.contains(b, b.x() + b.w(), b.y()));
        assertFalse(LiveStreamSettingsButton.contains(b, b.x(), b.y() + b.h()));
    }

    @Test
    void showsInTheInventoryOnly() {
        assertTrue(LiveStreamSettingsButton.showsOn(net.minecraft.client.gui.screens.inventory.InventoryScreen.class));
        assertTrue(LiveStreamSettingsButton.showsOn(net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen.class));
        assertFalse(LiveStreamSettingsButton.showsOn(net.minecraft.client.gui.screens.ChatScreen.class));
        assertFalse(LiveStreamSettingsButton.showsOn(net.minecraft.client.gui.screens.PauseScreen.class));
        assertFalse(LiveStreamSettingsButton.showsOn(net.minecraft.client.gui.screens.inventory.ContainerScreen.class));
        assertFalse(LiveStreamSettingsButton.showsOn((net.minecraft.client.gui.screens.Screen) null));
    }
}
