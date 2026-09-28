package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link PlayerMobGroupSpawner#resolveCompanionCarriagePIdx} — which carriage a
 * PlayerMob friend-pair companion is set up for. Pure: no registry or live entity needed.
 */
final class PlayerMobGroupSpawnerCompanionTest {

    private static final String PREFIX = CarriageContentsPlacer.DT_CONTENTS_TAG_PREFIX;

    @Test
    void spawnContextWinsOverLeaderTag() {
        assertEquals(OptionalInt.of(7),
            PlayerMobGroupSpawner.resolveCompanionCarriagePIdx(7, Set.of(PREFIX + "42")));
    }

    @Test
    void fallsBackToLeaderContentsTag() {
        assertEquals(OptionalInt.of(42),
            PlayerMobGroupSpawner.resolveCompanionCarriagePIdx(null, List.of("other", PREFIX + "42")));
    }

    @Test
    void emptyWhenNeitherContextNorTag() {
        assertEquals(OptionalInt.empty(),
            PlayerMobGroupSpawner.resolveCompanionCarriagePIdx(null, Set.of("some_other_tag")));
    }

    @Test
    void emptyWhenLeaderTagMalformed() {
        assertEquals(OptionalInt.empty(),
            PlayerMobGroupSpawner.resolveCompanionCarriagePIdx(null, Set.of(PREFIX + "abc")));
    }

    @Test
    void emptyWhenLeaderTagsNull() {
        assertEquals(OptionalInt.empty(),
            PlayerMobGroupSpawner.resolveCompanionCarriagePIdx(null, null));
    }
}
