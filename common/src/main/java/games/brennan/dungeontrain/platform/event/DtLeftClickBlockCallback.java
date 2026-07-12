package games.brennan.dungeontrain.platform.event;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Loader-neutral form of NeoForge's {@code PlayerInteractEvent.LeftClickBlock}
 * (client-side uses). CANCELLABLE: the callback returns {@code true} to cancel the
 * left-click (the former {@code event.setCanceled(true)}). DT uses two tiers —
 * HIGHEST (nine world-space menu handlers that cancel to protect their UI) and
 * NORMAL (one observer, {@code RideSnapshotDirector}, that reads {@code level}/{@code pos}
 * and never cancels). {@code NeoForgeClientInputBridge} subscribes once per tier so a
 * HIGHEST cancel skips the NORMAL observer exactly as before. Params are vanilla.
 */
@FunctionalInterface
public interface DtLeftClickBlockCallback {

    /**
     * @return {@code true} to cancel the left-click-block interaction
     */
    boolean onLeftClickBlock(Level level, BlockPos pos);
}
