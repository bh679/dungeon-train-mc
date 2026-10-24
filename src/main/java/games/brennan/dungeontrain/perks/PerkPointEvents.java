package games.brennan.dungeontrain.perks;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.AdvancementProfiles;
import games.brennan.dungeontrain.advancement.TabGateways;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps each player's unlock-point total in step with their advancements.
 *
 * <p>An earn (or a login, respawn or End return) only marks the player dirty; the next player tick
 * recomputes the total in one pass over the advancement tree. A login replays hundreds of earns, so
 * this keeps that to one recompute, and the first recompute after a login is silent (it is not a new
 * earn, just the existing ones being counted).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PerkPointEvents {

    static final String KEY_GAINED = "chat.dungeontrain.perks.unlock_gained";

    /** Dirty players; the value is true when the recompute should stay quiet. */
    private static final Map<UUID, Boolean> DIRTY = new ConcurrentHashMap<>();

    private PerkPointEvents() {}

    @SubscribeEvent
    public static void onEarn(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        boolean silent = AdvancementProfiles.isReplaying();
        DIRTY.merge(player.getUUID(), silent, (a, b) -> a && b);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) DIRTY.put(player.getUUID(), true);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        DIRTY.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!event.isEndConquered()) PerkPoints.resetForNewLife(player);
        // The cached total is not copied to the clone; count it again.
        DIRTY.put(player.getUUID(), true);
    }

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Boolean silent = DIRTY.remove(player.getUUID());
        if (silent == null) return;
        int before = PerkPoints.unlockEarned(player);
        int after = recompute(player);
        player.setData(ModDataAttachments.PERK_UNLOCK_EARNED.get(), after);
        if (!silent && after > before) {
            player.sendSystemMessage(Component.translatable(KEY_GAINED, after - before,
                PerkPoints.unlockAvailable(player)).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
    }

    /** Sum of {@link UnlockPointLedger#weight} over every advancement this player has done. */
    static int recompute(ServerPlayer player) {
        int total = 0;
        for (AdvancementHolder holder : player.server.getAdvancements().getAllAdvancements()) {
            DisplayInfo display = holder.value().display().orElse(null);
            if (display == null || !counts(holder.id())) continue;
            if (!player.getAdvancements().getOrStartProgress(holder).isDone()) continue;
            total += UnlockPointLedger.weight(display.getType(), display.isHidden());
        }
        return total;
    }

    /** Editor authoring and tab-head copies are not achievements. */
    static boolean counts(ResourceLocation id) {
        String path = id.getPath();
        if (path.startsWith("recipes/") || path.startsWith("editor/")) return false;
        return !TabGateways.isLinked(id);
    }
}
