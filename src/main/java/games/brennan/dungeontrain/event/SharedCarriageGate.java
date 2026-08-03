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
     * Whether this world may lease shared carriages from the relay pool — server master AND the host
     * not being in Kid mode.
     *
     * <p>Kid mode switches leasing off rather than filtering it: a leased carriage is another player's
     * build, which can carry signs and written books placed inside it, and there is no kid-safe
     * curation for carriages to filter on. HOST-scoped for the same reason lectern narratives are —
     * the train is world geometry with no per-player view of it. Contribution is deliberately NOT
     * gated: Kid mode restricts what a child is SHOWN, not what they may make, and uploads already
     * require network consent.</p>
     */
    public static boolean canDiscover() {
        return DungeonTrainConfig.isSharedCarriagesEnabled()
                && !WorldLanguage.hostBlocksSharedContent(ServerLifecycleHooks.getCurrentServer());
    }
}
