package games.brennan.dungeontrain.platform.event;

import net.minecraft.world.entity.player.Player;

/**
 * Loader-neutral form of NeoForge's {@code PlayerEvent.PlayerLoggedInEvent}:
 * fired on the server thread when a player joins. Not cancellable; exposes the
 * {@link Player} ({@code getEntity()}).
 *
 * <p><b>Priority coupling (preserved):</b> {@code CheatDetectionEvents.onLogin}
 * ran at HIGHEST and must execute BEFORE {@code AchievementEvents.onPlayerLoggedIn}
 * (NORMAL) — CheatDetection sets the Free Play flag that AchievementEvents then
 * reads. {@code NeoForgeConnectionBridge} subscribes login at HIGHEST and NORMAL
 * separately so that ordering (and cross-mod interleaving) is identical to the
 * old bus.</p>
 *
 * @param player the joining player (matches {@code PlayerLoggedInEvent.getEntity()})
 */
@FunctionalInterface
public interface DtPlayerLoginCallback {
    void onPlayerLoggedIn(Player player);
}
