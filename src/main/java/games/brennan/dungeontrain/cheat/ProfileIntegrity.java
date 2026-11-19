package games.brennan.dungeontrain.cheat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.AdvancementProfiles;
import games.brennan.dungeontrain.advancement.BurritoClock;
import games.brennan.dungeontrain.advancement.GlobalAchievementStore;
import games.brennan.dungeontrain.advancement.GlobalCriteriaProgress;
import games.brennan.dungeontrain.advancement.GlobalPlayerStats;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A player's own progress files were changed outside the game, so their run is <b>Free Play</b>
 * (see {@link RunIntegrity}).
 *
 * <p>The cross-world stores ({@link GlobalAchievementStore}, {@link GlobalCriteriaProgress},
 * {@link BurritoClock}, {@link GlobalPlayerStats}) seal what they write
 * ({@link games.brennan.dungeontrain.data.SealedJson}) and report here when a read finds a seal that
 * no longer matches. Per player, like the permanent {@code RUN_CHEATED} stamp, and permanent the same
 * way: the store that caught the edit writes a taint mark into that file under the seal, so the next
 * session reads it straight back. The honest way out is the profile reset in Video Tools, which
 * deletes the file.</p>
 *
 * <p>The stores load on first use, which for a joining player is later than the login notice; so
 * {@link #refreshOnJoin} touches them first.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ProfileIntegrity {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String CAUSE_KEY = "chat.dungeontrain.free_play.cause.profile_tampered";

    private static final Set<UUID> TAINTED = ConcurrentHashMap.newKeySet();

    private ProfileIntegrity() {}

    /** Is this player's run Free Play because one of their progress files was edited? */
    public static boolean isPlayerFreePlay(ServerPlayer player) {
        return isTainted(player.getUUID());
    }

    public static boolean isTainted(UUID uuid) {
        return TAINTED.contains(uuid);
    }

    /** A store found {@code what} edited: mark the player for this session. Idempotent. */
    public static void markTampered(UUID uuid, String what) {
        if (TAINTED.add(uuid)) {
            LOGGER.warn("[DungeonTrain] {} for {} was changed outside the game; their run is Free Play.", what, uuid);
        }
    }

    /** A profile reset deleted the files: forget the mark. Files still tainted re-mark on their next read. */
    public static void clear(UUID uuid) {
        TAINTED.remove(uuid);
    }

    /**
     * Load every sealed store for a joining player, so a taint is known before the login notices go
     * out. Each store caches its first read, so this costs nothing the login would not pay anyway.
     */
    public static void refreshOnJoin(ServerPlayer player) {
        UUID uuid = player.getUUID();
        String key = AdvancementProfiles.currentKey(player);
        GlobalAchievementStore.read(uuid, key);
        GlobalAchievementStore.read(uuid, GlobalAchievementStore.SHARED);
        GlobalCriteriaProgress.read(uuid, key);
        GlobalCriteriaProgress.read(uuid, GlobalAchievementStore.SHARED);
        GlobalPlayerStats.trainTicks(uuid);
        BurritoClock.playTicks(uuid);
    }

    public static Component cause() {
        return Component.translatable(CAUSE_KEY);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TAINTED.clear();
    }
}
