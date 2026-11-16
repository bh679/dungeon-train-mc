package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Completing The Darkroom (Fully Developed) earns the Live Eye's music disc, Vista's
 * {@code music_disc_sojourn}. Once per player per installation ({@link OneOffRewardMarkers}): an earn
 * replayed into a new world, or a revoke and regrant, gives nothing more.
 *
 * <p>Two ways in: the earn itself ({@link #onEarned}), and login ({@link #onLogin}), which backfills
 * anyone who already held Fully Developed before this reward existed.</p>
 */
public final class DarkroomDiscReward {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final ResourceLocation FULLY_DEVELOPED =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train/fully_developed");
    static final ResourceLocation DISC = ResourceLocation.fromNamespaceAndPath("vista", "music_disc_sojourn");
    static final String MARKER_KEY = "darkroom_disc";

    private DarkroomDiscReward() {}

    /** From {@code AdvancementEarnEvent}: Fully Developed just earned (live or replayed). */
    public static void onEarned(ServerPlayer player, ResourceLocation id) {
        if (FULLY_DEVELOPED.equals(id)) grantOnce(player);
    }

    /** From login, after the profile replay: the one-off backfill for players who already hold it. */
    public static void onLogin(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        AdvancementHolder holder = server.getAdvancements().get(FULLY_DEVELOPED);
        if (holder != null && player.getAdvancements().getOrStartProgress(holder).isDone()) grantOnce(player);
    }

    private static void grantOnce(ServerPlayer player) {
        try {
            OneOffRewardMarkers markers = OneOffRewardMarkers.live();
            if (markers.isGiven(player.getUUID(), MARKER_KEY)) return;
            Optional<Item> disc = discItem();
            if (disc.isEmpty()) {
                LOGGER.warn("[DungeonTrain] Darkroom disc: {} is not registered (Vista missing?), held for later", DISC);
                return;
            }
            // Mark first: a failed write must not turn every login into another disc.
            if (!markers.markGiven(player.getUUID(), MARKER_KEY)) return;
            ItemStack stack = new ItemStack(disc.get());
            if (!player.getInventory().add(stack)) player.drop(stack, false);
            LOGGER.info("[DungeonTrain] Darkroom disc given to {}", player.getName().getString());
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Darkroom disc: grant failed for {}: {}", player.getName().getString(), e.toString());
        }
    }

    private static Optional<Item> discItem() {
        return BuiltInRegistries.ITEM.getOptional(DISC).filter(item -> item != Items.AIR);
    }
}
