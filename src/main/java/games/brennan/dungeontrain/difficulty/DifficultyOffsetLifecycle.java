package games.brennan.dungeontrain.difficulty;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.slf4j.Logger;

/**
 * Scopes the admin difficulty travelled-offset to a world and a run.
 *
 * <p>The offset is stored in the <strong>global</strong> server config
 * ({@code config/dungeontrain-server.toml}) because its consumers are static hot-path calls —
 * see {@link DifficultyOffset}. Left alone, that means cranking difficulty (or running
 * {@code /dtp}) in one world silently carries into the next world you create, and surviving a
 * death keeps the whole run at the elevated tier even though per-life travelled progress resets
 * to 0. Two hooks fix that:</p>
 *
 * <ul>
 *   <li><b>World load</b> — mirror the per-world {@link DungeonTrainWorldData} value into the
 *       config, unconditionally, so a world that never set an offset actively clears whatever
 *       the previous world left behind. Runs on {@link LevelEvent.Load} at
 *       {@link EventPriority#HIGH}, which fires inside {@code MinecraftServer.createLevels} —
 *       before spawn-region chunk generation — so the first chunks already bake at the right
 *       tier ({@link DifficultyProgression#positionTier} gates carriage templates and loot).</li>
 *   <li><b>Respawn</b> — a death starts a new run ({@code PlayerRunState.resetCarts} zeroes
 *       {@code travelledCarriageIndex}), so the offset is cleared too.</li>
 * </ul>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DifficultyOffsetLifecycle {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DifficultyOffsetLifecycle() {}

    /**
     * Mirror this world's stored offset into the global config as the world loads. Guarded to
     * the server-side overworld — {@link LevelEvent.Load} also fires for client levels and for
     * every other dimension, and the SavedData lives on the overworld.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel overworld)) return;
        if (!overworld.dimension().equals(Level.OVERWORLD)) return;

        int stored = DungeonTrainWorldData.get(overworld).getDifficultyTravelledOffset();
        int previous = DungeonTrainConfig.getDifficultyTravelledOffset();
        DungeonTrainConfig.setDifficultyTravelledOffset(stored);
        if (stored != previous) {
            LOGGER.info("[DungeonTrain] Difficulty offset for this world: {} (global config was {})",
                stored, previous);
        }
    }

    /**
     * A new run starts with automatic difficulty. The offset is server-global rather than
     * per-player, so in multiplayer it is only cleared when the respawning player is the last
     * one online — otherwise one death would yank everyone else's difficulty mid-run.
     */
    @SubscribeEvent(priority = EventPriority.HIGH) // ahead of AchievementEvents' HUD packet
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.isEndConquered()) return; // End → overworld portal, not a death.
        if (DungeonTrainConfig.getDifficultyTravelledOffset() == DungeonTrainConfig.DEFAULT_DIFFICULTY_TRAVELLED_OFFSET) {
            return;
        }

        MinecraftServer server = player.getServer();
        if (server == null) return;
        if (server.getPlayerList().getPlayers().size() > 1) {
            LOGGER.debug("[DungeonTrain] Respawn: keeping the difficulty offset — {} other player(s) still in their run",
                server.getPlayerList().getPlayers().size() - 1);
            return;
        }

        DifficultyOffset.clear(server);
        LOGGER.info("[DungeonTrain] Respawn: new run for {} — difficulty offset cleared, back to automatic",
            player.getName().getString());
    }
}
