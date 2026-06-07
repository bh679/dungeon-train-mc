package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TrainMembership} train-membership detection.
 *
 * <p>Exercises the pure tag-matching core {@link TrainMembership#hasContentsTag}
 * (tagged / untagged / empty / null) plus the {@code null}-entity guard on
 * {@link TrainMembership#isOnTrain}. {@code DT_CONTENTS_TAG_PREFIX} is a
 * compile-time-inlined {@code String} constant, so these run without a
 * bootstrapped Minecraft registry or a live entity.
 */
final class TrainMembershipTest {

    private static final String PREFIX = CarriageContentsPlacer.DT_CONTENTS_TAG_PREFIX;

    @Test
    void nullEntityIsNotOnTrain() {
        assertFalse(TrainMembership.isOnTrain(null));
    }

    @Test
    void contentsTagWithPidxMatches() {
        // The real tag carriage contents carry, e.g. dungeontrain_contents_pidx_42.
        assertTrue(TrainMembership.hasContentsTag(Set.of(PREFIX + "42")));
    }

    @Test
    void barePrefixMatches() {
        assertTrue(TrainMembership.hasContentsTag(Set.of(PREFIX)));
    }

    @Test
    void contentsTagAmongOtherTagsMatches() {
        assertTrue(TrainMembership.hasContentsTag(List.of("minecraft:something", PREFIX + "0", "zzz")));
    }

    @Test
    void unrelatedTagsDoNotMatch() {
        assertFalse(TrainMembership.hasContentsTag(Set.of("minecraft:baby", "some_other_mod_tag")));
    }

    @Test
    void emptyTagsDoNotMatch() {
        assertFalse(TrainMembership.hasContentsTag(Set.of()));
    }

    @Test
    void nullTagsAreSafe() {
        assertFalse(TrainMembership.hasContentsTag(null));
    }
}
