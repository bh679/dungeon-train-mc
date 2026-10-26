package games.brennan.dungeontrain.perks;

import net.minecraft.server.level.ServerPlayer;

/**
 * A server-side effect handler for one or more perks. Register with
 * {@link PerkEffects#register(PerkEffect)} during mod construction.
 *
 * <p>Two calls, both on the server thread:</p>
 * <ul>
 *   <li>{@link #onRankChanged}: the player's rank for a perk changed (debug command, points
 *       eaten, death reset). Re-apply whatever depends on the rank: attribute modifiers, stamped
 *       components. {@code newRank} may be 0.</li>
 *   <li>{@link #onPlayerReady}: the player is a fresh entity (login, respawn, dimension change)
 *       and the state has already been synced. Re-apply every rank-dependent thing from
 *       {@link Perks#rank} because modifiers on the old entity are gone.</li>
 * </ul>
 *
 * <p>Handlers that only ever <em>query</em> rank from their own event listeners (most of them)
 * need neither and can leave both as the defaults.</p>
 */
public interface PerkEffect {

    default void onRankChanged(ServerPlayer player, String perkId, int oldRank, int newRank) {}

    default void onPlayerReady(ServerPlayer player) {}
}
