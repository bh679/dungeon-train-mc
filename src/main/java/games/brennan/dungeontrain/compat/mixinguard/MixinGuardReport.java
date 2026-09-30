package games.brennan.dungeontrain.compat.mixinguard;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which guarded third-party mixins {@code ThirdPartyMixinPlugin} skipped this boot because their
 * targets had changed. Written once during mixin bootstrap, read by the features that fall back.
 */
public final class MixinGuardReport {

    private static final Set<String> SKIPPED = ConcurrentHashMap.newKeySet();

    private MixinGuardReport() {}

    /** Record that {@code mixinClassName} was not applied. */
    public static void recordSkipped(String mixinClassName) {
        SKIPPED.add(mixinClassName);
    }

    /** Every mixin skipped so far. */
    public static Set<String> skipped() {
        return Set.copyOf(SKIPPED);
    }

    /**
     * True while every mixin that makes BetterEnd's End generate the same from one boot to the next is
     * applied. When one is skipped, the End laid out in one session no longer matches the next, so the
     * BetterEnd End bands stamp vanilla instead ({@code EndBandSampler#appliesTo}).
     */
    public static boolean endDeterminismIntact() {
        return endDeterminismIntact(SKIPPED);
    }

    static boolean endDeterminismIntact(Collection<String> skipped) {
        return skipped.stream().noneMatch(ThirdPartyMixinTargets::isEndDeterminism);
    }
}
