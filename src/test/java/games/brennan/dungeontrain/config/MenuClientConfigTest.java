package games.brennan.dungeontrain.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MenuClientConfigTest {

    @Test
    @DisplayName("Three choices, in the order the options button cycles them; live + photos before the config loads")
    void choices() {
        assertArrayEquals(new MenuClientConfig.Backdrop[] {
                MenuClientConfig.Backdrop.LIVE_AND_PHOTOS, MenuClientConfig.Backdrop.PHOTOS_ONLY, MenuClientConfig.Backdrop.NONE },
                MenuClientConfig.Backdrop.values());
        assertEquals(MenuClientConfig.Backdrop.LIVE_AND_PHOTOS, MenuClientConfig.backdrop());
    }
}
