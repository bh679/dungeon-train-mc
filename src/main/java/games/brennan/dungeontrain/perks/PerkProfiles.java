package games.brennan.dungeontrain.perks;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.advancement.AdvancementProfiles;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps a player's live perk unlocks in step with their save for the current difficulty
 * ({@link PerkProfileStore}), the way {@link AdvancementProfiles} does for the advancement tree: each
 * difficulty has its own unlocks, because Advancement Points are earned per difficulty and an unlock
 * carried across would spend points that difficulty never earned.
 *
 * <p>The save follows the player across worlds, so a new world brings back that difficulty's unlocks.
 * Free Play purchases are not saved, the same rule as Free Play advancements: they last in that world's
 * {@code PERK_STATE} only.</p>
 */
public final class PerkProfiles {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PerkProfiles() {}

    /**
     * The unlock levels a player logs in with.
     *
     * @param hasSave  the player has a save file, on any difficulty
     * @param saved    the save's levels for this difficulty
     * @param world    the levels in this world's copy
     * @param freePlay this run is Free Play, so the world's copy may hold levels the save never saw
     */
    static Map<String, Integer> loginUnlocks(boolean hasSave, Map<String, Integer> saved,
                                             Map<String, Integer> world, boolean freePlay) {
        if (freePlay) return higherOf(saved, world);
        return hasSave ? saved : world;
    }

    /** For each perk, the higher of the two levels. */
    static Map<String, Integer> higherOf(Map<String, Integer> a, Map<String, Integer> b) {
        Map<String, Integer> out = new LinkedHashMap<>(a);
        b.forEach((id, level) -> out.merge(id, level, Math::max));
        return out;
    }

    /** A world from before this save existed: its unlocks become the current difficulty's save. */
    static boolean adoptsWorldCopy(boolean hasSave, Map<String, Integer> world, boolean freePlay) {
        return !hasSave && !freePlay && !world.isEmpty();
    }

    /** Line the live state up with the save. Runs on login, ahead of the client sync. */
    static void onLogin(ServerPlayer player) {
        UUID uuid = player.getUUID();
        String key = AdvancementProfiles.currentKey(player);
        boolean freePlay = RunIntegrity.isCheated(player);
        boolean hasSave = PerkProfileStore.exists(uuid);
        PerkState state = Perks.state(player);
        Map<String, Integer> saved = hasSave ? PerkProfileStore.unlocks(uuid, key) : Map.of();
        if (adoptsWorldCopy(hasSave, state.unlocks(), freePlay)) {
            PerkProfileStore.setUnlocks(uuid, key, state.unlocks());
            LOGGER.info("[DungeonTrain] perk save: adopted {} unlock(s) from this world for {} ({})",
                state.unlocks().size(), player.getScoreboardName(), key);
        }
        Map<String, Integer> levels = known(loginUnlocks(hasSave, saved, state.unlocks(), freePlay));
        Perks.replaceState(player, PerkState.of(levels, state.active()));
    }

    /** Save an unlock level to the difficulty being played, unless this run is Free Play. */
    static void onUnlockLevelChanged(ServerPlayer player, String perkId, int level) {
        if (RunIntegrity.isCheated(player)) return;
        PerkProfileStore.setUnlockLevel(player.getUUID(), AdvancementProfiles.currentKey(player), perkId, level);
    }

    /** Dev reset: forget the current difficulty's save too, or the next login would bring it all back. */
    static void onCleared(ServerPlayer player) {
        PerkProfileStore.setUnlocks(player.getUUID(), AdvancementProfiles.currentKey(player), Map.of());
    }

    /**
     * Move the player onto {@code to}'s perks: active ranks and apple points wait on {@code from} like
     * the inventory does, and {@code to}'s unlocks and anything parked there come back.
     *
     * <p>Takes explicit keys because the level still reports the old difficulty during
     * {@code DifficultyChangeEvent}. Call after {@link AdvancementProfiles#swap}: the swap revokes the old
     * tree without firing an earn event, so the Advancement Point total is marked for a recount here.</p>
     */
    public static void swap(ServerPlayer player, String from, String to) {
        if (from.equals(to)) return;
        UUID uuid = player.getUUID();
        PerkProfileStore.Parked outgoing = new PerkProfileStore.Parked(
            Perks.state(player).active(), PerkPoints.active(player));
        PerkProfileStore.Parked waiting = PerkProfileStore.swapParked(uuid, from, outgoing, to);
        Perks.replaceState(player, PerkState.of(known(PerkProfileStore.unlocks(uuid, to)), waiting.active()));
        PerkPoints.setActive(player, waiting.points());
        PerkPointEvents.markDirtySilently(player);
        PerkBalances.sync(player);
    }

    /** Only perks this build still has go live; the save keeps the rest. */
    private static Map<String, Integer> known(Map<String, Integer> levels) {
        PerkRegistry.bootstrap();
        Map<String, Integer> out = new LinkedHashMap<>();
        levels.forEach((id, level) -> { if (PerkRegistry.isKnown(id)) out.put(id, level); });
        return out;
    }
}
