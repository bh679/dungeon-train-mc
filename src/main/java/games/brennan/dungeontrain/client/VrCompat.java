package games.brennan.dungeontrain.client;

import net.neoforged.fml.ModList;

/**
 * Runtime Vivecraft (VR) presence check, used to suppress {@link FramerateThrottle} entirely on
 * VR installs.
 *
 * <p><b>Why presence and not "is VR mode active".</b> Vivecraft is an optional, player-added mod —
 * not a compile dependency and not in the Dungeon Train modpack (same situation
 * {@link games.brennan.dungeontrain.mixin.VivecraftMixinPlugin} documents). Without compiling
 * against it there is no supported way to ask whether the headset is currently active, so this
 * deliberately errs wide: if Vivecraft is installed at all, the throttle stays off.</p>
 *
 * <p>That over-suppresses for a player who installed Vivecraft but is running it in its non-VR
 * mode — they simply keep vanilla's (unthrottled) behaviour, which is exactly what shipped before
 * this feature existed. Capping a headset to 30fps causes motion sickness, so the asymmetry is
 * intentional: the cost of over-suppressing is a missed optimisation, the cost of
 * under-suppressing is a player feeling ill.</p>
 *
 * <p>Distinct from {@code VivecraftMixinPlugin}, which answers the same question during early
 * class transformation via {@code LoadingModList}. This one runs long after load, so it uses the
 * ordinary runtime {@link ModList}.</p>
 */
public final class VrCompat {

    private static final String VIVECRAFT_MODID = "vivecraft";

    /**
     * Resolved on first successful read and reused — mod presence is fixed for the JVM lifetime.
     * Boxed so that a pre-{@code ModList} call (which cannot answer) is retried rather than
     * cached as a false negative.
     */
    private static volatile Boolean cached;

    private VrCompat() {}

    /** Is Vivecraft installed? {@code false} if the mod list can't be read yet (fail-open: throttle stays allowed). */
    public static boolean isVivecraftPresent() {
        Boolean known = cached;
        if (known != null) return known;
        try {
            ModList list = ModList.get();
            if (list == null) return false; // too early — don't cache, ask again next frame
            boolean present = list.isLoaded(VIVECRAFT_MODID);
            cached = present;
            return present;
        } catch (Throwable t) {
            // Loader state unreadable for any reason — treat as "not VR" for now and retry later.
            return false;
        }
    }
}
