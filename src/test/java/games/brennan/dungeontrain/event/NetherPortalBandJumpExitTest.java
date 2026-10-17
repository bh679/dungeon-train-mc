package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.event.NetherPortalBandJump.ExitChoice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link NetherPortalBandJump#chooseExit}: a link is followed only between two world frames. A frame on a
 * carriage (a ship-plot position) at either end drops the link and band-jumps — following it once sent a
 * player to the raw plot coordinate, x≈20 million, to fall forever.
 */
final class NetherPortalBandJumpExitTest {

    @Test
    @DisplayName("no partner band-jumps, wherever the frame stands")
    void noPartner() {
        assertEquals(ExitChoice.BAND_JUMP, NetherPortalBandJump.chooseExit(false, false, false));
        assertEquals(ExitChoice.BAND_JUMP, NetherPortalBandJump.chooseExit(false, true, false));
    }

    @Test
    @DisplayName("a partner between two world frames is followed")
    void worldPair() {
        assertEquals(ExitChoice.LINKED, NetherPortalBandJump.chooseExit(true, false, false));
    }

    @Test
    @DisplayName("a partner in the plot grid is dropped — the return from a portal built on the train")
    void partnerOnCarriage() {
        assertEquals(ExitChoice.UNLINK_AND_JUMP, NetherPortalBandJump.chooseExit(true, false, true));
    }

    @Test
    @DisplayName("a frame on a carriage never follows a link")
    void originOnCarriage() {
        assertEquals(ExitChoice.UNLINK_AND_JUMP, NetherPortalBandJump.chooseExit(true, true, false));
        assertEquals(ExitChoice.UNLINK_AND_JUMP, NetherPortalBandJump.chooseExit(true, true, true));
    }
}
