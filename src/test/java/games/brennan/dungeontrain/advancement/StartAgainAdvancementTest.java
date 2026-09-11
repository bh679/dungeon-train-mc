package games.brennan.dungeontrain.advancement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down the two pure honesty gates on "It's Not That Simple"
 * ({@link StartAgainAdvancement#shouldArm} / {@link StartAgainAdvancement#shouldBank}). The
 * surrounding arm/grant path needs a live {@code ServerPlayer} and
 * {@code ServerAdvancementManager}, so these tests exercise the part that actually encodes the
 * rules — "the burrito must have been earned, not granted" and "a Free Play run never banks".
 */
final class StartAgainAdvancementTest {

    @Test
    @DisplayName("Arms only when the capstone is both live and banked")
    void needsAnEarnedCapstone() {
        assertTrue(StartAgainAdvancement.shouldArm(false, true, true));
        // A burrito conjured by /advancement grant is live but was never banked — the case that
        // let a Free Play run bank this reward.
        assertFalse(StartAgainAdvancement.shouldArm(false, true, false));
        // Banked from an earlier clean run, but not in this world's tree: nothing to wipe.
        assertFalse(StartAgainAdvancement.shouldArm(false, false, true));
        assertFalse(StartAgainAdvancement.shouldArm(false, false, false));
    }

    @Test
    @DisplayName("Already banked never re-arms, however the capstone stands")
    void alreadyBankedBlocks() {
        assertFalse(StartAgainAdvancement.shouldArm(true, true, true));
        assertFalse(StartAgainAdvancement.shouldArm(true, true, false));
        assertFalse(StartAgainAdvancement.shouldArm(true, false, true));
    }

    @Test
    @DisplayName("An unbanked live copy doesn't lock the player out of earning it honestly")
    void liveOnlyLeftoverDoesNotBlock() {
        // /advancement grant @s only …/start_again leaves a live copy that never banked. The
        // arming rule reads the banked set, so the honest earn is still available.
        assertTrue(StartAgainAdvancement.shouldArm(false, true, true));
    }

    @Test
    @DisplayName("The self-selector grant admits a bare @s token and nothing that merely starts with it")
    void bareSelfSelectorOnly() {
        assertTrue(SelfSelectorGrant.isBareSelfSelector("@s"));
        assertTrue(SelfSelectorGrant.isBareSelfSelector("@s everything"));
        assertFalse(SelfSelectorGrant.isBareSelfSelector("@a"));
        assertFalse(SelfSelectorGrant.isBareSelfSelector("@a everything"));
        assertFalse(SelfSelectorGrant.isBareSelfSelector("@s2"));
        assertFalse(SelfSelectorGrant.isBareSelfSelector("@self"));
        assertFalse(SelfSelectorGrant.isBareSelfSelector("@s[limit=1]"));
        assertFalse(SelfSelectorGrant.isBareSelfSelector("Dev everything"));
        assertFalse(SelfSelectorGrant.isBareSelfSelector(""));
    }

    @Test
    @DisplayName("Only a clean run banks")
    void freePlayNeverBanks() {
        assertTrue(StartAgainAdvancement.shouldBank(false));
        assertFalse(StartAgainAdvancement.shouldBank(true));
    }
}
