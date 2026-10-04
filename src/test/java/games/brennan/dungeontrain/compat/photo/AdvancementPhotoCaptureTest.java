package games.brennan.dungeontrain.compat.photo;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which earns remember their photo: genuine Enchiridion camera earns with a photo behind them, nothing else. */
final class AdvancementPhotoCaptureTest {

    private static ResourceLocation rl(String id) {
        return ResourceLocation.parse(id);
    }

    @Test
    @DisplayName("a genuine camera earn with a photo is sent")
    void cameraEarn() {
        assertTrue(AdvancementPhotoCapture.shouldSend(rl("dungeontrain:enchiridion/say_cheese"), "dt_photo_1", false));
    }

    @Test
    @DisplayName("replays, missing photos, other tabs and other mods are not")
    void everythingElse() {
        assertFalse(AdvancementPhotoCapture.shouldSend(rl("dungeontrain:enchiridion/say_cheese"), "dt_photo_1", true));
        assertFalse(AdvancementPhotoCapture.shouldSend(rl("dungeontrain:enchiridion/say_cheese"), null, false));
        assertFalse(AdvancementPhotoCapture.shouldSend(rl("dungeontrain:enchiridion/say_cheese"), " ", false));
        assertFalse(AdvancementPhotoCapture.shouldSend(rl("dungeontrain:dungeon_train/the_enchiridion"), "dt_photo_1", false));
        assertFalse(AdvancementPhotoCapture.shouldSend(rl("dungeontrain:dungeon_train/carts_100"), "dt_photo_1", false));
        assertFalse(AdvancementPhotoCapture.shouldSend(rl("minecraft:story/root"), "dt_photo_1", false));
    }
}
