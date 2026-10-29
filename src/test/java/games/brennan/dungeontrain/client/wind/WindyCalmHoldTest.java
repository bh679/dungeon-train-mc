package games.brennan.dungeontrain.client.wind;

import games.brennan.dungeontrain.net.WindProfilePacket;
import games.brennan.dungeontrain.wind.WindProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindyCalmHoldTest {

    @AfterEach
    void tearDown() {
        ClientWind.reset();
        WindyDrive.release();
    }

    @Test
    @DisplayName("a DT server's wind packet makes the wind known even with the wind off; logout forgets it")
    void knownOnceToldEvenWhenOff() {
        ClientWind.reset();
        assertFalse(ClientWind.known());
        ClientWind.apply(new WindProfilePacket(WindProfile.CALM.toJson(), 1, 1000L, false));
        assertTrue(ClientWind.known());
        assertFalse(ClientWind.active());
        ClientWind.reset();
        assertFalse(ClientWind.known());
    }

    @Test
    @DisplayName("calm keeps hold of Windy at no wind and no spawns; release hands it back")
    void calmHoldsWindyAtNothing() {
        WindyDrive.set(0.02, 1.0, 0.0, 0.0, 0.0);
        WindyDrive.calm();
        assertTrue(WindyDrive.owns());
        assertEquals(0.0f, WindyDrive.strength());
        assertEquals(0.0, WindyDrive.spawnRate());
        WindyDrive.release();
        assertFalse(WindyDrive.owns());
    }
}
