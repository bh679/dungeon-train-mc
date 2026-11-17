package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Completing The Darkroom (Fully Developed) earns the Live Eye's music disc, Vista's
 * {@code music_disc_sojourn}. The disc itself is the advancement's own reward, the same way Biomes
 * O' Plenty pays its Wanderer disc: {@code rewards.loot} in {@code fully_developed.json} →
 * {@code loot_table/advancements/fully_developed.json}. Replays into new worlds don't pay it again
 * ({@code AdvancementRewardsReplayMixin}).
 *
 * <p>This class is only the one-off backfill for players who held Fully Developed before the reward
 * existed: on login, a holder with no record of the disc gets those same rewards once. A genuine earn
 * records it ({@link #onEarned}), so a new earner isn't paid twice. The record lives outside the world
 * save ({@link OneOffRewardMarkers}), so each player is backfilled once per installation.</p>
 */
public final class DarkroomDiscReward {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final ResourceLocation FULLY_DEVELOPED =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train/fully_developed");
    static final String MARKER_KEY = "darkroom_disc";

    private DarkroomDiscReward() {}

    /** From {@code AdvancementEarnEvent}: a genuine earn paid the reward itself, so just record it. */
    public static void onEarned(ServerPlayer player, ResourceLocation id) {
        if (!FULLY_DEVELOPED.equals(id) || AdvancementProfiles.isRestoringEarned()) return;
        OneOffRewardMarkers.live().markGiven(player.getUUID(), MARKER_KEY);
    }

    /** From login, after the profile replay: the one-off backfill for players who already hold it. */
    public static void onLogin(ServerPlayer player) {
        try {
            MinecraftServer server = player.getServer();
            if (server == null) return;
            AdvancementHolder holder = server.getAdvancements().get(FULLY_DEVELOPED);
            if (holder == null || !player.getAdvancements().getOrStartProgress(holder).isDone()) return;
            OneOffRewardMarkers markers = OneOffRewardMarkers.live();
            if (markers.isGiven(player.getUUID(), MARKER_KEY)) return;
            // Record first: a failed write must not turn every login into another disc.
            if (!markers.markGiven(player.getUUID(), MARKER_KEY)) return;
            holder.value().rewards().grant(player);
            LOGGER.info("[DungeonTrain] Darkroom disc backfilled for {}", player.getName().getString());
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Darkroom disc: backfill failed for {}: {}", player.getName().getString(), e.toString());
        }
    }
}
