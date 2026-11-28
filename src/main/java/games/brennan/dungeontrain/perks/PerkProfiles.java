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
 * Free Play unlocks never reach that save, the same rule as Free Play advancements. They go to the
 * profile's own Free Play partition instead ({@link #freePlayKey}), so they last from one Free Play run
 * to the next. A Free Play run starts from the higher of the two.</p>
 *
 * <p>Nothing here runs while perks are off ({@link PerksGate}). A perks test world is Test mode, its
 * own profile like a difficulty: its unlocks save to their own partition ({@link #TEST_KEY}), so a
 * tester keeps them from one test world to the next while the per-difficulty saves are never touched.</p>
 */
public final class PerkProfiles {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The save partition perks test worlds use, apart from every difficulty's. */
    static final String TEST_KEY = PerksGate.TEST_PROFILE;

    /** Players whose live state already holds their Free Play save. Server thread only. */
    private static final java.util.Set<UUID> FREE_PLAY_LOADED = new java.util.HashSet<>();

    private PerkProfiles() {}

    /** The partition this player's unlocks are saved to right now. */
    private static String key(ServerPlayer player) {
        return PerksGate.isTestWorld() ? TEST_KEY : AdvancementProfiles.currentKey(player);
    }

    /** Free Play keeps its unlocks apart from the profile's. Test mode is not Free Play: it saves to {@link #TEST_KEY}. */
    private static boolean skipsSave(ServerPlayer player) {
        return RunIntegrity.isCheated(player);
    }

    /** Where Free Play keeps {@code key}'s unlocks: next to that profile, never in it. */
    static String freePlayKey(String key) {
        return key + "_freeplay";
    }

    /** The unlocks a run starts from on {@code key}: in Free Play, the higher of the profile's and its Free Play partition's. */
    private static Map<String, Integer> savedUnlocks(UUID uuid, String key, boolean freePlay) {
        Map<String, Integer> saved = PerkProfileStore.unlocks(uuid, key);
        return freePlay ? higherOf(saved, PerkProfileStore.unlocks(uuid, freePlayKey(key))) : saved;
    }

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
        if (!PerksGate.isOn()) return;
        UUID uuid = player.getUUID();
        String key = key(player);
        boolean freePlay = skipsSave(player);
        boolean hasSave = PerkProfileStore.exists(uuid);
        PerkState state = Perks.state(player);
        Map<String, Integer> saved = hasSave ? savedUnlocks(uuid, key, freePlay) : Map.of();
        if (adoptsWorldCopy(hasSave, state.unlocks(), freePlay)) {
            PerkProfileStore.setUnlocks(uuid, key, state.unlocks());
            LOGGER.info("[DungeonTrain] perk save: adopted {} unlock(s) from this world for {} ({})",
                state.unlocks().size(), player.getScoreboardName(), key);
        }
        Map<String, Integer> levels = known(loginUnlocks(hasSave, saved, state.unlocks(), freePlay));
        Perks.replaceState(player, PerkState.of(levels, state.active()));
        if (freePlay) FREE_PLAY_LOADED.add(uuid); else FREE_PLAY_LOADED.remove(uuid);
    }

    /**
     * Free Play can start mid-run (a command, cheats, an operator joining): once it does, the Free Play
     * save's unlocks go live on top of this run's. Polled ({@link PerkLifecycle}) because Free Play has
     * many sources and not all of them announce themselves. Never lowers a level.
     */
    static void checkFreePlay(ServerPlayer player) {
        if (!PerksGate.isOn()) return;
        UUID uuid = player.getUUID();
        if (!skipsSave(player)) {
            FREE_PLAY_LOADED.remove(uuid);
            return;
        }
        if (!FREE_PLAY_LOADED.add(uuid) || !PerkProfileStore.exists(uuid)) return;
        PerkState state = Perks.state(player);
        Map<String, Integer> levels = known(higherOf(state.unlocks(), savedUnlocks(uuid, key(player), true)));
        if (levels.equals(state.unlocks())) return;
        Perks.replaceState(player, PerkState.of(levels, state.active()));
        LOGGER.info("[DungeonTrain] perk save: Free Play unlocks loaded for {}", player.getScoreboardName());
    }

    static void onLogout(ServerPlayer player) {
        FREE_PLAY_LOADED.remove(player.getUUID());
    }

    /** Save an unlock level to the difficulty being played, or to its Free Play partition in Free Play. */
    static void onUnlockLevelChanged(ServerPlayer player, String perkId, int level) {
        if (!PerksGate.isOn()) return;
        String key = skipsSave(player) ? freePlayKey(key(player)) : key(player);
        PerkProfileStore.setUnlockLevel(player.getUUID(), key, perkId, level);
    }

    /** Where a player's past lives are kept: one global list for real play, the test partition's own in a test world. */
    private static String livesPartition() {
        return PerksGate.isTestWorld() ? TEST_KEY : null;
    }

    /**
     * A life just ended: remember the perks it activated, for the nap sky. Nothing is kept while perks
     * are off (that life is "before perks") or on a Free Play run outside a test world.
     *
     * @return the history after the append, or null when nothing was recorded
     */
    static PerkLifeHistory onLifeEnded(ServerPlayer player) {
        if (!PerksGate.isOn() || skipsSave(player)) return null;
        java.util.List<String> perks = java.util.List.copyOf(Perks.state(player).active().keySet());
        PerkLifeHistory next = PerkProfileStore.appendLife(player.getUUID(), livesPartition(), perks);
        LOGGER.debug("[DungeonTrain] perk life recorded for {}: {} perk(s), {} life/lives kept",
            player.getScoreboardName(), perks.size(), next.size());
        return next;
    }

    /** The past lives' perks the nap sky draws; empty while perks are off. */
    static PerkLifeHistory pastLives(ServerPlayer player) {
        if (!PerksGate.isOn()) return PerkLifeHistory.EMPTY;
        return PerkProfileStore.pastLives(player.getUUID(), livesPartition());
    }

    /**
     * True when {@code player}'s profile says they have taken a Cosmic Apple in some past life
     * ({@link FirstAppleBoost}). A test world never reads the profile: its finds live in the world.
     */
    static boolean firstAppleFound(ServerPlayer player) {
        if (!PerksGate.isOn() || PerksGate.isTestWorld()) return false;
        return PerkProfileStore.firstAppleFound(player.getUUID());
    }

    /**
     * Remember for good that the player {@code uuid} has taken a Cosmic Apple. Free Play saves it too: the
     * find was real. A test world keeps its finds in the world instead.
     */
    static void markFirstAppleFound(java.util.UUID uuid) {
        if (!PerksGate.isOn() || PerksGate.isTestWorld()) return;
        PerkProfileStore.markFirstAppleFound(uuid);
    }

    /** Dev reset: forget the current difficulty's save too, or the next login would bring it all back. */
    static void onCleared(ServerPlayer player) {
        if (!PerksGate.isOn()) return;
        PerkProfileStore.setUnlocks(player.getUUID(), key(player), Map.of());
        PerkProfileStore.setUnlocks(player.getUUID(), freePlayKey(key(player)), Map.of());
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
        // Perks off: no perk files are read or written. A test world keeps one partition whatever
        // the difficulty, so there is nothing to swap.
        if (!PerksGate.isOn() || PerksGate.isTestWorld()) return;
        UUID uuid = player.getUUID();
        PerkProfileStore.Parked outgoing = new PerkProfileStore.Parked(
            Perks.state(player).active(), PerkPoints.active(player));
        PerkProfileStore.Parked waiting = PerkProfileStore.swapParked(uuid, from, outgoing, to);
        Perks.replaceState(player, PerkState.of(known(savedUnlocks(uuid, to, skipsSave(player))), waiting.active()));
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
