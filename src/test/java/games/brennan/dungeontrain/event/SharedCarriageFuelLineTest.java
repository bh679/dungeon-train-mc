package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.event.SharedCarriageEnterEvents.PendingLine;
import games.brennan.dungeontrain.train.SharedCarriageMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A drifting carriage's entry message once its engine is in question: silent after every engine has been
 * carried off, and a "waits for fuel" line, last and on its own beat, while an engine aboard sits empty.
 */
class SharedCarriageFuelLineTest {

    @Test
    void aCarriageWithItsEnginesCarriedOffIsNotAnnounced() {
        assertFalse(SharedCarriageMessage.announces(0, 0));
    }

    @Test
    void aCarriageWithAnEngineIsAnnouncedFedOrNot() {
        assertTrue(SharedCarriageMessage.announces(1, 0), "a fresh carriage, engine waiting for coal");
        assertTrue(SharedCarriageMessage.announces(2, 5));
    }

    @Test
    void anOldBuildWithNoEngineButFuelLeftKeepsItsMessage() {
        assertTrue(SharedCarriageMessage.announces(0, 2));
    }

    @Test
    void noFuelLineWithoutAnEngineOrWithCoalInIt() {
        RandomSource rng = RandomSource.create(1);
        assertNull(SharedCarriageMessage.fuelLine(0, 0, rng), "nothing aboard to feed");
        assertNull(SharedCarriageMessage.fuelLine(1, 3, rng), "already fed");
    }

    @Test
    void anEmptyEngineGetsOneOfTheThreeFuelLines() {
        RandomSource rng = RandomSource.create(7);
        for (int i = 0; i < 50; i++) {
            Component line = SharedCarriageMessage.fuelLine(1, 0, rng);
            assertNotNull(line);
            String key = ((TranslatableContents) line.getContents()).getKey();
            assertTrue(key.matches("chat\\.dungeontrain\\.shared_carriage\\.fuel\\.[123]"), key);
        }
    }

    @Test
    void theFuelLineTakesTheCreditBeatWhenNothingElseIsHeld() {
        assertEquals(50, SharedCarriageEnterEvents.fuelDue(List.of(), 50));
    }

    @Test
    void theFuelLineWaitsABeatBehindTheLastHeldLine() {
        List<PendingLine> credit = List.of(new PendingLine(Component.literal("credit"), 50));
        List<PendingLine> both = List.of(new PendingLine(Component.literal("credit"), 50),
                new PendingLine(Component.literal("deaths"), 95));
        assertEquals(95, SharedCarriageEnterEvents.fuelDue(credit, 50));
        assertEquals(140, SharedCarriageEnterEvents.fuelDue(both, 50));
    }
}
