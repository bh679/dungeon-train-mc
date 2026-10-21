package games.brennan.dungeontrain.compat.vista;

import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TvSpawnPowerTest {

    @Test
    void rollIsTheSameEveryTimeForTheSameScreen() {
        Vec3i rel = new Vec3i(3, 1, 2);
        boolean first = TvSpawnPower.rollOn(42L, 17, rel);
        for (int i = 0; i < 10; i++) assertEquals(first, TvSpawnPower.rollOn(42L, 17, rel));
    }

    @Test
    void roughlyTheConfiguredShareOfScreensComeOn() {
        int on = 0;
        int total = 0;
        for (int carriage = 0; carriage < 400; carriage++) {
            for (int x = 0; x < 5; x++) {
                total++;
                if (TvSpawnPower.rollOn(1234L, carriage, new Vec3i(x, 1, 2))) on++;
            }
        }
        double share = (double) on / total;
        assertTrue(Math.abs(share - TvSpawnPower.SPAWN_ON_CHANCE) < 0.04, "share was " + share);
    }

    @Test
    void anyOnlinePlayerWhoOptedOutKeepsEveryTvOff() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(TvSpawnPower.allowedFor(List.of(a, b), Set.of()));
        assertFalse(TvSpawnPower.allowedFor(List.of(a, b), Set.of(b)));
        assertTrue(TvSpawnPower.allowedFor(List.of(a), Set.of(b)), "an opted-out player who left no longer counts");
    }
}
