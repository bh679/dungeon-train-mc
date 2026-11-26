package games.brennan.dungeontrain.perks.aura;

import games.brennan.dungeontrain.perks.effects.lover.LoverAura;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuraRollerTest {

    private static AuraDef def() {
        return new AuraDef("aura", LoverAura.POOL, LoverAura.FIRST, LoverAura.NO_LEVELS, LoverAura.LADDER,
            120, 20, false, (o, e) -> true, "buff", 0);
    }

    @Test
    void firstFromTheTrioAndNoRepeats() {
        for (long seed = 0; seed < 50; seed++) {
            Random random = new Random(seed);
            AuraDraws d = AuraRoller.extend(def(), AuraDraws.EMPTY, LoverAura.shape(40), Set.of(), random::nextInt);
            assertTrue(LoverAura.FIRST.contains(d.effects().get(0)));
            assertEquals(10, d.effects().size());
            assertEquals(10, new HashSet<>(d.effects()).size());
        }
    }

    @Test
    void earlierRollsNeverChange() {
        Random random = new Random(7);
        AuraDraws small = AuraRoller.extend(def(), AuraDraws.EMPTY, LoverAura.shape(8), Set.of(), random::nextInt);
        AuraDraws big = AuraRoller.extend(def(), small, LoverAura.shape(20), Set.of(), random::nextInt);
        assertEquals(small.effects(), big.effects().subList(0, small.effects().size()));
    }

    @Test
    void levelRoundsSkipOffAndLevellessAndReachTwoBeforeThree() {
        Random random = new Random(3);
        AuraDraws d = AuraRoller.extend(def(), AuraDraws.EMPTY, LoverAura.shape(32), Set.of(), random::nextInt);
        ResourceLocation off = d.effects().get(0);
        // 8 that take levels, one switched off: 7 rounds bring each of the rest to II
        int rank = 32 + 14;
        AuraDraws levelled = AuraRoller.extend(def(), d, LoverAura.shape(rank), Set.of(off), random::nextInt);
        int rounds = LoverAura.shape(rank).levelUps();
        assertEquals(7, rounds);
        for (int i = 0; i < 10; i++) {
            ResourceLocation e = levelled.effects().get(i);
            int expected = e.equals(off) || LoverAura.NO_LEVELS.contains(e) ? 1 : 2;
            assertEquals(expected, levelled.level(i, rounds), e.toString());
        }
    }

    @Test
    void topUpRule() {
        assertTrue(AuraTicker.needsTopUp(-1, 0, false, 0, 20));
        assertTrue(AuraTicker.needsTopUp(0, 20, false, 0, 20));
        assertTrue(!AuraTicker.needsTopUp(0, 21, false, 0, 20));
        assertTrue(!AuraTicker.needsTopUp(1, 5, false, 0, 20));
        assertTrue(AuraTicker.needsTopUp(0, 400, false, 1, 20));
        assertTrue(!AuraTicker.needsTopUp(0, 5, true, 0, 20));
    }

    @Test
    void previewKinds() {
        assertEquals(AuraPreview.Kind.ONE_RANGE, AuraPreview.between(LoverAura.shape(1), LoverAura.shape(2)).kind());
        assertEquals(AuraPreview.Kind.NEW, AuraPreview.between(LoverAura.shape(4), LoverAura.shape(5)).kind());
        assertEquals(AuraPreview.Kind.ALL_AND_NEW, AuraPreview.between(LoverAura.shape(7), LoverAura.shape(8)).kind());
        assertEquals(AuraPreview.Kind.ALL, AuraPreview.between(LoverAura.shape(32), LoverAura.shape(33)).kind());
        assertEquals(AuraPreview.Kind.LEVEL, AuraPreview.between(LoverAura.shape(33), LoverAura.shape(34)).kind());
    }
}
