package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.MultiplayerState;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import javax.annotation.Nullable;

/**
 * The Challenges tab is single-player only. Consulted by {@code PlayerAdvancementsMultiplayerGateMixin}
 * at the head of {@code PlayerAdvancements.award}, the one call every grant goes through — the same
 * seam as {@link AfkAdvancementGate}.
 *
 * <p>The challenges are one-life rules (no chests, no broken blocks, no damage dealt, nothing but
 * melon, ...) that other players could keep for you, so in multiplayer they are simply not granted.
 * "Multiplayer" is {@link MultiplayerState#isMultiplayer}, the definition the Played Multiplayer
 * advancement and the world-info telemetry share: a dedicated server, a guest on someone's world, or
 * a LAN host once a friend is on — sticky for that server run.</p>
 *
 * <p>Membership is by tab: any advancement whose tree root is {@link #TAB_ROOT}. Two awards pass:</p>
 * <ul>
 *   <li>The tab root itself, so the tab still unlocks and shows its "single-player only" note.</li>
 *   <li>{@link AdvancementProfiles#isReplaying()} — challenges already earned solo replay as usual.</li>
 * </ul>
 * <p>The out-of-tab Challenge Complete needs every tab member, so it can't be reached either.</p>
 */
public final class MultiplayerChallengeGate {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The Challenges tab's root advancement. */
    static final ResourceLocation TAB_ROOT =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train/tab_challenges");

    private MultiplayerChallengeGate() {}

    /** Should this grant be refused? {@code player} is null before the tracker owner is attached. */
    public static boolean shouldBlock(@Nullable ServerPlayer player, AdvancementHolder holder, String criterion) {
        if (player == null || player.server == null) return false;
        if (!blocks(MultiplayerState.isMultiplayer(player.server), AdvancementProfiles.isReplaying(),
                rootOf(player, holder), holder.id())) {
            return false;
        }
        LOGGER.debug("[DungeonTrain] Refused {} / {} for {}: challenges are single-player only",
            holder.id(), criterion, player.getName().getString());
        return true;
    }

    /** The pure rule, for tests: a Challenges-tab member (not the root) awarded live in multiplayer. */
    static boolean blocks(boolean multiplayer, boolean replaying,
                          @Nullable ResourceLocation rootId, ResourceLocation id) {
        return multiplayer && !replaying && TAB_ROOT.equals(rootId) && !TAB_ROOT.equals(id);
    }

    @Nullable
    private static ResourceLocation rootOf(ServerPlayer player, AdvancementHolder holder) {
        AdvancementNode node = player.server.getAdvancements().tree().get(holder);
        return node == null ? null : node.root().holder().id();
    }
}
