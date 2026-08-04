package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import net.minecraft.server.level.ServerPlayer;

/**
 * Gate for the Death Note curse mechanic — mirrors {@link SharedBookGate}. Signing/burning is local
 * and only needs the operator master ({@link #isEnabled}); anything that talks to the relay
 * (uploading a completed curse on the author's death, downloading a target's curses) additionally
 * requires the player's network consent and is fail-closed ({@link #canSync}).
 */
public final class DeathNoteGate {

    private DeathNoteGate() {}

    /** Operator master for the whole mechanic (local sign/burn included). */
    public static boolean isEnabled() {
        return DungeonTrainConfig.isDeathNotesEnabled();
    }

    /**
     * True when this player's Death Note traffic may reach the relay — the feature is enabled, the
     * client granted network consent, AND the player is not in Kid mode. Null-safe and fail-closed
     * (mirrors {@link SharedBookGate#canContribute}).
     *
     * <p>Kid mode gates this PER-PLAYER, not host-scoped like lecterns and carriages, because a curse
     * is addressed to one specific player: a child in Kid mode neither uploads a curse nor has one
     * delivered to them, even on an adult's server. Both directions are covered here because this is
     * the single seam all Death Note relay traffic passes through.</p>
     */
    public static boolean canSync(ServerPlayer player) {
        if (player == null) return false;
        return isEnabled() && NetworkConsentMirror.isGranted(player) && !ContentModeMirror.isKid(player);
    }
}
