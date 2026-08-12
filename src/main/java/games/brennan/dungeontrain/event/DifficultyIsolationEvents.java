package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.EnderChestLockBridge;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.player.DifficultyPartition;
import games.brennan.dungeontrain.player.LoadoutStore;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.DifficultyChangeEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

/**
 * Swaps every online player onto the new difficulty's profile when the world difficulty changes: their
 * carried inventory + XP ({@link LoadoutStore}) and their Ender Chest (via
 * {@link EnderChestLockBridge#engage}, which drives EnderChestPersistence's live slot swap).
 *
 * <p>Each vanilla difficulty is a self-contained run — see {@link DifficultyPartition}. Dungeon Train
 * carries gear across runs (a new world per death, with {@code keepInventory}) and keeps the Ender Chest
 * outside the save entirely, so without this a player could farm on Peaceful and cash in on Hard.</p>
 *
 * <p><b>The swap is announced.</b> Silently emptying someone's inventory is indistinguishable from a bug,
 * and the reassurance that the gear is waiting on the old difficulty is the entire difference between
 * "my stuff is gone" and "I switched profiles".</p>
 *
 * <p>{@link DifficultyChangeEvent} fires after the server has taken the new value, so the old difficulty
 * is only available from the event itself — which is why the partition to store into is read from
 * {@code getOldDifficulty()} rather than from any level.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DifficultyIsolationEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DifficultyIsolationEvents() {}

    @SubscribeEvent
    public static void onDifficultyChange(DifficultyChangeEvent event) {
        if (!DungeonTrainConfig.getDifficultyIsolatedStash()) {
            return;
        }
        Difficulty from = event.getOldDifficulty();
        Difficulty to = event.getDifficulty();
        if (from == to) {
            return;
        }
        String fromKey = DifficultyPartition.keyFor(from);
        String toKey = DifficultyPartition.keyFor(to);

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return; // client-side echo of the change, or no world running — nothing to swap
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                LoadoutStore.swap(player, fromKey, toKey);
                // ECP re-resolves the slot key, which now carries the new difficulty's suffix.
                EnderChestLockBridge.engage(player);
                player.sendSystemMessage(Component.translatable(
                    "chat.dungeontrain.difficulty_profile.swapped",
                    Component.translatable(to.getKey()),
                    Component.translatable(from.getKey())).withStyle(ChatFormatting.YELLOW));
            } catch (Exception e) {
                // One player's failed swap must not skip everyone after them.
                LOGGER.warn("[DungeonTrain] difficulty profile swap {} → {} failed for {}",
                    fromKey, toKey, player.getName().getString(), e);
            }
        }
        LOGGER.info("[DungeonTrain] difficulty profile changed {} → {} for {} player(s)",
            fromKey, toKey, server.getPlayerList().getPlayerCount());
    }
}
