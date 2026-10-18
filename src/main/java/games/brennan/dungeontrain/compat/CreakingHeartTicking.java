package games.brennan.dungeontrain.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Lets {@code CreakingHeartSettingsMixin}'s static {@code serverTick} hook reach the heart's own
 * extras bookkeeping (a mixin class cannot be cast to at runtime; an interface it adds can).
 */
public interface CreakingHeartTicking {

    void dungeontrain$tickExtras(ServerLevel level, BlockPos pos);
}
