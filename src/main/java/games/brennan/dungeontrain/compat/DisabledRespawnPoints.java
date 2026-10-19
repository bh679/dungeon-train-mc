package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/**
 * Spawn points set at a {@link DisabledModContent#DISABLED_BLOCKS disabled block} before it was turned off.
 * BetterNether's Respawner Statue stores a forced spawn point that outlives the block's click, so
 * {@link DisabledBlockUseGate} alone can't undo it: on login and on death the player's spawn point is
 * dropped back to the world spawn when it sits on one of those blocks.
 */
public final class DisabledRespawnPoints {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DisabledRespawnPoints() {}

    /** Clears {@code player}'s spawn point when it is a disabled block; true when it did. */
    public static boolean clearIfDisabled(ServerPlayer player) {
        BlockPos pos = player.getRespawnPosition();
        if (pos == null) return false;
        ServerLevel level = player.server.getLevel(player.getRespawnDimension());
        if (level == null) return false;
        var block = level.getBlockState(pos).getBlock();
        if (!DisabledModContent.isDisabledBlock(BuiltInRegistries.BLOCK.getKey(block))) return false;
        player.setRespawnPosition(Level.OVERWORLD, null, 0.0F, false, false);
        LOGGER.info("[DungeonTrain] Cleared {}'s spawn point at a disabled {} ({} {})",
            player.getGameProfile().getName(), BuiltInRegistries.BLOCK.getKey(block),
            level.dimension().location(), pos.toShortString());
        return true;
    }
}
