package games.brennan.dungeontrain.client.sound;

import net.minecraft.sounds.SoundSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathMenuHushFadeTest {

    @Test
    void keepsUiMusicAndVoice() {
        assertFalse(DeathMenuHushFade.hushes(SoundSource.MASTER));
        assertFalse(DeathMenuHushFade.hushes(SoundSource.MUSIC));
        assertFalse(DeathMenuHushFade.hushes(SoundSource.VOICE));
        assertFalse(DeathMenuHushFade.hushes(null));
    }

    @Test
    void hushesTheWorld() {
        for (SoundSource s : new SoundSource[] {SoundSource.HOSTILE, SoundSource.NEUTRAL, SoundSource.BLOCKS,
            SoundSource.AMBIENT, SoundSource.WEATHER, SoundSource.PLAYERS, SoundSource.RECORDS}) {
            assertTrue(DeathMenuHushFade.hushes(s), s.name());
        }
    }

    @Test
    void rampsDownToFloorAndNeverZero() {
        float f = 1.0F;
        for (int i = 0; i < DeathMenuHushFade.DOWN_TICKS; i++) f = DeathMenuHushFade.step(f, true);
        assertEquals(DeathMenuHushFade.FLOOR, f, 1e-6F);
        f = DeathMenuHushFade.step(f, true);
        assertEquals(DeathMenuHushFade.FLOOR, f, 1e-6F);
        assertTrue(DeathMenuHushFade.apply(1.0F, f) > 0.0F);
    }

    @Test
    void rampsBackUpToFull() {
        float f = DeathMenuHushFade.FLOOR;
        for (int i = 0; i < DeathMenuHushFade.UP_TICKS - 1; i++) f = DeathMenuHushFade.step(f, false);
        assertTrue(f < 1.0F);
        f = DeathMenuHushFade.step(f, false);
        assertEquals(1.0F, f, 1e-6F);
        assertEquals(0.7F, DeathMenuHushFade.apply(0.7F, f), 1e-6F);
    }
}
