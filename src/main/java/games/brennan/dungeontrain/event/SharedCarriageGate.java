package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.narrative.WorldLanguage;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Feature gate for shared carriages, mirroring {@link SharedBookGate}.
 *
 * <ul>
 *   <li>{@link #canContribute(ServerPlayer)} — may this player's build be UPLOADED to the relay? Needs
 *       both the server master ({@code sharedCarriagesEnabled}) AND the player's client network consent
 *       ({@link NetworkConsentMirror}), fail-closed — same posture as book contribution.</li>
 *   <li>{@link #canDiscover()} — may this world LEASE carriages from the pool? Server-wide master only;
 *       leased builds are already approved/public, so no per-player consent (mirrors book discovery).</li>
 * </ul>
 */
public final class SharedCarriageGate {

    private SharedCarriageGate() {}

    /** Whether {@code player}'s carriage edits may be uploaded to the relay (master + network consent). */
    public static boolean canContribute(ServerPlayer player) {
        if (player == null) return false;
        return DungeonTrainConfig.isSharedCarriagesEnabled() && NetworkConsentMirror.isGranted(player);
    }

    /**
     * Whether this world may lease shared carriages from the relay pool (server master only).
     *
     * <p>Kid mode does NOT switch leasing off — it narrows it. The relay now carries a kid-safe verdict
     * per carriage, so a Kid world leases from that subset instead of getting nothing; see
     * {@link #leasesKidSafeOnly()}, which is what the lease request actually sends. Contribution is
     * deliberately ungated either way: Kid mode restricts what a child is SHOWN, not what they may
     * make, and uploads already require network consent.</p>
     */
    public static boolean canDiscover() {
        return DungeonTrainConfig.isSharedCarriagesEnabled();
    }

    /**
     * Whether carriage leases must be restricted to builds the relay flagged kid-safe.
     *
     * <p>HOST-scoped, unlike the per-player book filter: the train is world geometry with one shared
     * appearance, so there is no per-player view to filter. On a multiplayer world the host's mode
     * governs — the same limitation the language filter has.</p>
     *
     * <p>⚠️ The relay's verdict reads a build's TEXT only. A carriage with no writing in it is passed
     * without review at all (most of the pool), and one shaped or coloured badly passes either way —
     * nothing in that pipeline can look at geometry. This is a real filter, not a guarantee.</p>
     */
    public static boolean leasesKidSafeOnly() {
        return WorldLanguage.hostBlocksSharedContent(ServerLifecycleHooks.getCurrentServer());
    }
}
