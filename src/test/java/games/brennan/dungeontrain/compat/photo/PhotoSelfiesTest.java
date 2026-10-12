package games.brennan.dungeontrain.compat.photo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Darkroom selfie advancements: only a selfie counts, and the head slot picks which. */
final class PhotoSelfiesTest {

    @Test
    @DisplayName("a selfie wearing the Live Eye or a TV earns its advancement; a front-facing shot earns neither")
    void actions() {
        assertEquals(List.of(PhotoSelfies.SELFIE_LIVE_EYE), PhotoSelfies.actions(true, true, false));
        assertEquals(List.of(PhotoSelfies.SELFIE_TV_HEAD), PhotoSelfies.actions(true, false, true));
        assertEquals(List.of(), PhotoSelfies.actions(true, false, false), "a selfie with nothing on your head");
        assertEquals(List.of(), PhotoSelfies.actions(false, true, false), "Live Eye on, but not a selfie");
        assertEquals(List.of(), PhotoSelfies.actions(false, false, true), "TV on, but not a selfie");
    }
}
