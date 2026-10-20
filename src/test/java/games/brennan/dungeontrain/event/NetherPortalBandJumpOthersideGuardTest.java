package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.compat.mixinguard.ThirdPartyMixinTargets;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NetherPortalBandJump#guardedOtherside}: a Deeper Darker build that moved a member DT's Otherside portal
 * code calls fails to link at call time; that must leave the trip to the mod (vanilla outcome) and report the mixin
 * as off, while any other failure still propagates. The recorder is injected so the real {@code MixinGuardReport}
 * (which another test asserts is empty this boot) is never written.
 */
final class NetherPortalBandJumpOthersideGuardTest {

    @Test
    void linkageErrorsFallBackToVanillaAndReportTheMixinAsSkipped() {
        List<String> recorded = new ArrayList<>();
        for (LinkageError e : List.of(new NoSuchMethodError("getExitPortal"),
                new NoClassDefFoundError("OthersideTeleporter"), new NoSuchFieldError("OTHERSIDE_PORTAL"))) {
            assertSame(NetherPortalBandJump.Outcome.VANILLA,
                    NetherPortalBandJump.guardedOtherside(() -> { throw e; }, recorded::add));
        }
        assertEquals(List.of(NetherPortalBandJump.OTHERSIDE_MIXIN, NetherPortalBandJump.OTHERSIDE_MIXIN,
                NetherPortalBandJump.OTHERSIDE_MIXIN), recorded);
    }

    @Test
    void theReportedNameIsTheGuardedMixin() {
        assertTrue(ThirdPartyMixinTargets.guardedMixins().contains(NetherPortalBandJump.OTHERSIDE_MIXIN));
        assertFalse(ThirdPartyMixinTargets.isEndDeterminism(NetherPortalBandJump.OTHERSIDE_MIXIN));
    }

    @Test
    void aNormalOutcomePassesThrough() {
        List<String> recorded = new ArrayList<>();
        NetherPortalBandJump.Outcome handled = new NetherPortalBandJump.Outcome(true, null);
        assertSame(handled, NetherPortalBandJump.guardedOtherside(() -> handled, recorded::add));
        assertFalse(NetherPortalBandJump.guardedOtherside(() -> NetherPortalBandJump.Outcome.VANILLA, recorded::add).handled());
        assertTrue(recorded.isEmpty());
    }

    @Test
    void otherFailuresStillPropagate() {
        assertThrows(IllegalStateException.class, () -> NetherPortalBandJump.guardedOtherside(
                () -> { throw new IllegalStateException("not a link error"); }, name -> {}));
    }
}
