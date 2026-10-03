package games.brennan.dungeontrain.compat.mixinguard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which skips cost the BetterEnd End bands their sampled terrain. */
final class MixinGuardReportTest {

    private static final String PKG = ThirdPartyMixinTargets.MIXIN_PACKAGE;

    @Test
    void nothingSkippedKeepsTheEnd() {
        assertTrue(MixinGuardReport.endDeterminismIntact(List.of()));
    }

    @Test
    void cosmeticSkipsKeepTheEnd() {
        assertTrue(MixinGuardReport.endDeterminismIntact(List.of(
                PKG + "wover.WoverStartupScreensMixin",
                PKG + "bclib.BclibFixPromptMixin",
                PKG + "betterend.BetterEndChorusCosmeticMixin",
                PKG + "terrablender.SurfaceRuleManagerMixin")));
    }

    @Test
    void anyLayoutSkipLosesTheEnd() {
        assertFalse(MixinGuardReport.endDeterminismIntact(List.of(PKG + "wover.WoverBiomePickerOrderMixin")));
        assertFalse(MixinGuardReport.endDeterminismIntact(List.of(PKG + "betterend.BetterEndStaticShuffleMixin")));
    }

    @Test
    void thisBootSkippedNothing() {
        // The JUnit launcher boots the mod set against the pinned libraries, so the plugin applied everything.
        assertTrue(MixinGuardReport.skipped().isEmpty(), "skipped: " + MixinGuardReport.skipped());
    }
}
