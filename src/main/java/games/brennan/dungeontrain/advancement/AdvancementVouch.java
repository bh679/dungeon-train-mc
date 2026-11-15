package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.ProfileIntegrity;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.relay.AdvancementVouchClient;
import games.brennan.dungeontrain.player.DifficultyPartition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The way back for a profile whose Dungeon Train advancements are held on trust
 * ({@link GlobalAchievementStore#unverified}). While any are, the burrito and the tab-completes make
 * no record ({@link CompletionistAdvancement#isVerifiedEarn}), and a profile can end up like that
 * through no fault of the player: before 2026-11-15 any partition the seal's first session never
 * rewrote was banked as absorbed the session after.
 *
 * <p>At login the absorbed gameplay ids are sent to the relay ({@link AdvancementVouchClient}),
 * which answers with the ones it holds a clean claim for from this player, the claim every clean
 * earn sends. Those move back to earned ({@link GlobalAchievementStore#vouch}); the rest stay on
 * trust. Relay off, unreachable or too old: nothing changes. A tainted profile never asks.</p>
 */
public final class AdvancementVouch {

    private static final Logger LOGGER = LogUtils.getLogger();

    private AdvancementVouch() {}

    /** Server thread, at login once the profile is loaded. Cheap when nothing is held on trust. */
    public static void onLogin(ServerPlayer player) {
        if (!DungeonTrainConfig.isWorldInfoToRelay()) return;
        UUID uuid = player.getUUID();
        if (ProfileIntegrity.isTainted(uuid)) return;
        String partition = AdvancementProfiles.currentKey(player);
        List<String> onTrust = gameplayIds(GlobalAchievementStore.unverified(uuid, partition));
        if (onTrust.isEmpty()) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        String difficulty = DifficultyPartition.keyFor(player.level());
        String name = player.getGameProfile().getName();
        AdvancementVouchClient.ask(uuid, difficulty, onTrust, confirmed -> server.execute(() -> {
            int moved = GlobalAchievementStore.vouch(uuid, partition, parse(confirmed));
            LOGGER.info("[DungeonTrain] {} held {} advancement(s) on trust; the relay vouched for {}.",
                name, onTrust.size(), moved);
        }));
    }

    /** The Dungeon Train gameplay ids among {@code ids}: the ones {@link CompletionistAdvancement#isVerifiedEarn} judges. */
    static List<String> gameplayIds(Set<ResourceLocation> ids) {
        return ids.stream().filter(AdvancementVouch::isGameplay).map(ResourceLocation::toString).toList();
    }

    static boolean isGameplay(ResourceLocation id) {
        return DungeonTrain.MOD_ID.equals(id.getNamespace()) && !id.getPath().startsWith("editor/");
    }

    private static List<ResourceLocation> parse(List<String> ids) {
        return ids.stream().map(ResourceLocation::tryParse).filter(id -> id != null).toList();
    }
}
