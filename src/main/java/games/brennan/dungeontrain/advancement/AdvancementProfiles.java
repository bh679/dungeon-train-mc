package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.player.DifficultyPartition;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps a player's live advancement tree in step with their cross-world profile — one profile per
 * difficulty, the way the Ender Chest and the carried loadout already are (see
 * {@link DifficultyPartition}). {@link GlobalAchievementStore} only stores; this class decides which
 * partition every read and write belongs to, and swaps the tree when the partition changes.
 *
 * <p><b>Which partition.</b> An advancement in the Train Editor tab goes to
 * {@link GlobalAchievementStore#SHARED}; everything else to the player's difficulty profile, or to
 * Normal's when {@code difficultyIsolatedStash} is off (one profile for everyone, as before).</p>
 *
 * <p><b>Whose tree is this?</b> Vanilla keeps the tree per world, so a world's tree has to be told
 * which profile it mirrors: {@link ModDataAttachments#ADVANCEMENT_PARTITION}. A tree written before the
 * split holds the combined history, so a blank note means Normal. On login, and when the difficulty
 * changes, a tree that mirrors the wrong profile is banked into its own and swapped — that covers a
 * pre-split world on Hard, a difficulty changed while the player was offline, and the config toggle.</p>
 */
public final class AdvancementProfiles {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Set while a replay or swap awards advancements the player already earned. {@code award(...)}
     * re-fires {@code AdvancementEarnEvent} for each, and without this guard the keybind hint,
     * accolades, milestone posts and capstone re-checks would treat every one as a fresh earn.
     * Server-thread scoped, mirroring the gamerule / Discord announce suppression around the same
     * loop.
     */
    private static volatile boolean replaying = false;

    /**
     * Set while a profile swap revokes the outgoing profile from the tree ({@link #clearProfile}).
     * Those revokes move progress between profiles; they are not the player giving it up, so the
     * criteria store must not hear them.
     */
    private static volatile boolean clearing = false;

    private AdvancementProfiles() {}

    /** True while awards are a replay of an earned profile, not genuine earns. */
    public static boolean isReplaying() {
        return replaying;
    }

    /**
     * The persist policy: an advancement is persisted iff it appears in the advancements screen (has a
     * display). This captures vanilla, Dungeon Train, and other mods' advancements alike, while
     * excluding the hidden display-less {@code recipes/*} tree — the same partition
     * {@link CompletionistAdvancement} uses to skip recipe-style advancements.
     */
    public static boolean shouldPersist(AdvancementHolder holder) {
        return holder.value().display().isPresent();
    }

    /** The difficulty profile {@code player} is playing on right now. */
    public static String currentKey(ServerPlayer player) {
        return DungeonTrainConfig.getDifficultyIsolatedStash()
            ? DifficultyPartition.keyFor(player.level())
            : DifficultyPartition.LEGACY_KEY;
    }

    /** The partition {@code id} is banked in for {@code player}: shared for the editor tab, else their profile. */
    public static String partitionFor(ServerPlayer player, ResourceLocation id) {
        return GlobalAchievementStore.isShared(id) ? GlobalAchievementStore.SHARED : currentKey(player);
    }

    /** The profile this world's tree mirrors — blank (written before the split) means Normal. */
    public static String treeKey(ServerPlayer player) {
        String key = player.getData(ModDataAttachments.ADVANCEMENT_PARTITION.get());
        return key.isEmpty() ? DifficultyPartition.LEGACY_KEY : key;
    }

    // ---------------- Earn / login / logout ----------------

    /**
     * Bank a live earn into its partition, subject to the persist policy and {@link RunIntegrity}
     * (cheated runs earn live but don't write to the cross-world profile; editor authoring is exempt).
     *
     * <p>A replayed award is never recorded: it came out of the store, so it is already banked — and
     * mid-swap it is banked in the profile being swapped <em>in</em>, while
     * {@code DifficultyChangeEvent} still runs with the level reporting the <em>old</em> difficulty.
     * Recording it would copy it into the profile being left.</p>
     *
     * @return true when the store was actually written
     */
    public static boolean record(ServerPlayer player, AdvancementHolder advancement) {
        return !replaying
            && shouldPersist(advancement)
            && RunIntegrity.persistsAdvancement(player, advancement)
            && GlobalAchievementStore.append(player.getUUID(), partitionFor(player, advancement.id()), advancement.id());
    }

    /**
     * Login, before anything is granted: if this world's tree mirrors a different profile from the one
     * the player is on, bank it into its own profile and swap it for theirs. Must run before any login
     * grant so a fresh world's tree is still empty — otherwise the first earn would be banked into the
     * wrong profile.
     */
    public static void alignOnLogin(ServerPlayer player) {
        String from = treeKey(player);
        String to = currentKey(player);
        if (!from.equals(to)) {
            LOGGER.info("[DungeonTrain] Advancement tree for {} mirrors the {} profile but the world is {} — swapping",
                player.getName().getString(), from, to);
            swapTree(player, from, to);
        }
        player.setData(ModDataAttachments.ADVANCEMENT_PARTITION.get(), to);
    }

    /**
     * Absorb every already-earned, GUI-visible advancement in this world into the cross-world store,
     * so it survives into other worlds. Complements the earn-time {@link #record}, which only captures
     * advancements earned <em>after</em> the store began tracking: a player who updated to this
     * version already has complete vanilla / other-mod trees that will never re-fire
     * {@code AdvancementEarnEvent}. Idempotent set-union, one write per partition.
     *
     * @param profileKey the difficulty profile the tree's non-editor advancements belong to
     */
    public static void absorb(ServerPlayer player, String profileKey) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Set<ResourceLocation> profile = new LinkedHashSet<>();
        Set<ResourceLocation> shared = new LinkedHashSet<>();
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            if (!shouldPersist(holder)) continue;
            // Tab copies follow their originals (TabGateways.sync) — never banked, as on earn.
            if (TabGateways.isLinked(holder.id())) continue;
            // Don't absorb a cheated world's gameplay/vanilla earns into the
            // cross-world profile; editor/* authoring still flows through.
            if (!RunIntegrity.persistsAdvancement(player, holder)) continue;
            if (!player.getAdvancements().getOrStartProgress(holder).isDone()) continue;
            (GlobalAchievementStore.isShared(holder.id()) ? shared : profile).add(holder.id());
        }
        UUID uuid = player.getUUID();
        int added = GlobalAchievementStore.appendAll(uuid, profileKey, profile)
            + GlobalAchievementStore.appendAll(uuid, GlobalAchievementStore.SHARED, shared);
        if (added > 0) {
            LOGGER.info("[DungeonTrain] Absorbed {} already-earned advancement(s) into the {} profile for {}",
                added, profileKey, player.getName().getString());
        }
    }

    /**
     * Replay {@code profileKey}'s profile and the shared partition onto this world's tree, re-granting
     * every advancement the player earned on that difficulty regardless of which world it happened in.
     *
     * <p>Silent: the announce gamerule and Discord Presence's announce are suppressed for the loop,
     * and {@link #isReplaying} is set so the earn-event side effects stand down. Toasts are silenced by
     * vanilla on login (the first packet resets the client); a mid-game swap resyncs the tree the same
     * way — see {@link #swap}.</p>
     */
    public static void replay(ServerPlayer player, String profileKey) {
        Set<ResourceLocation> granted = new LinkedHashSet<>(GlobalAchievementStore.read(player.getUUID(), profileKey));
        granted.addAll(GlobalAchievementStore.read(player.getUUID(), GlobalAchievementStore.SHARED));
        if (granted.isEmpty()) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerAdvancementManager mgr = server.getAdvancements();
        int[] replayed = {0};
        silently(server, () -> {
            for (ResourceLocation id : granted) {
                AdvancementHolder holder = mgr.get(id);
                if (holder == null) {
                    LOGGER.warn("[DungeonTrain] Globally-granted advancement {} not in registry — skipping", id);
                    continue;
                }
                for (String key : holder.value().criteria().keySet()) {
                    if (player.getAdvancements().award(holder, key)) replayed[0]++;
                }
            }
        });
        if (replayed[0] > 0) {
            LOGGER.info("[DungeonTrain] Replayed {} criteria from the {} profile for {} (silent)",
                replayed[0], profileKey, player.getName().getString());
        }
    }

    /**
     * Logout: any persisted advancement in the player's profile (or the shared partition) that is
     * <em>not</em> done in this world has been revoked via {@code /advancement revoke}, since the login
     * replay keeps them in sync. Drop those from the store so the revoke sticks across worlds.
     */
    public static void reconcileRevokes(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        int removed = reconcile(player, server.getAdvancements(), treeKey(player))
            + reconcile(player, server.getAdvancements(), GlobalAchievementStore.SHARED);
        if (removed > 0) {
            LOGGER.info("[DungeonTrain] Logout reconcile: removed {} revoked advancement(s) from the store for {}",
                removed, player.getName().getString());
        }
    }

    private static int reconcile(ServerPlayer player, ServerAdvancementManager mgr, String partitionKey) {
        UUID uuid = player.getUUID();
        int removed = 0;
        for (ResourceLocation id : GlobalAchievementStore.read(uuid, partitionKey)) {
            AdvancementHolder holder = mgr.get(id);
            if (holder == null) continue; // removed mod/datapack — leave it (may return)
            if (player.getAdvancements().getOrStartProgress(holder).isDone()) continue;
            if (GlobalAchievementStore.remove(uuid, partitionKey, id)) removed++;
        }
        return removed;
    }

    // ---------------- Half-finished collections ----------------

    /**
     * Does {@code holder} keep criterion-level progress across worlds? Any GUI-visible advancement
     * with more than one criterion — a collection such as Most Wanted, Adventuring Time or a biome
     * album. A single-criterion advancement is either done (and in {@link GlobalAchievementStore}) or
     * not started.
     */
    static boolean tracksCriteria(AdvancementHolder holder) {
        return shouldPersist(holder) && holder.value().criteria().size() > 1 && !TabGateways.isLinked(holder.id());
    }

    /**
     * A criterion landed on, or left, the live tree. Bank it into the cross-world criteria store, or
     * strike it, so the next world starts the collection where this one is. A finished advancement's
     * entry is dropped — {@link #record} banks the advancement itself.
     *
     * <p>Quiet during a replay or a profile swap: those move what is already banked. A cheated run
     * ({@link RunIntegrity}) banks nothing, as with full earns, but its revokes still count.</p>
     */
    public static void recordProgress(ServerPlayer player, AdvancementHolder holder, AdvancementProgress progress,
                                      String criterion, boolean granted) {
        if (replaying || clearing || !tracksCriteria(holder)) return;
        UUID uuid = player.getUUID();
        String partition = partitionFor(player, holder.id());
        boolean persists = RunIntegrity.persistsAdvancement(player, holder);
        if (progress.isDone()) {
            // Only once the finished advancement itself is banked: a cheated finish isn't, so the
            // honest progress behind it stays.
            if (persists) GlobalCriteriaProgress.removeAdvancement(uuid, partition, holder.id());
            return;
        }
        if (persists) {
            // Everything this world holds, not just the new criterion, so a revoke from a finished
            // advancement leaves the rest of it banked rather than nothing.
            GlobalCriteriaProgress.addAll(uuid, partition, holder.id(), completedCriteria(progress));
        }
        if (!granted) GlobalCriteriaProgress.removeCriterion(uuid, partition, holder.id(), criterion);
    }

    /**
     * Bank this world's half-finished collections into {@code profileKey} (the shared partition for
     * the editor tab). Login and the swap's outgoing profile — the counterpart of {@link #absorb}, and
     * what recovers progress made before the criteria store existed.
     */
    public static void absorbCriteria(ServerPlayer player, String profileKey) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        UUID uuid = player.getUUID();
        int added = 0;
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            if (!tracksCriteria(holder)) continue;
            if (!RunIntegrity.persistsAdvancement(player, holder)) continue;
            AdvancementProgress progress = player.getAdvancements().getOrStartProgress(holder);
            if (progress.isDone()) continue;
            List<String> held = completedCriteria(progress);
            if (held.isEmpty()) continue;
            String partition = GlobalAchievementStore.isShared(holder.id()) ? GlobalAchievementStore.SHARED : profileKey;
            added += GlobalCriteriaProgress.addAll(uuid, partition, holder.id(), held);
        }
        if (added > 0) {
            LOGGER.info("[DungeonTrain] Absorbed {} in-progress criteria into the {} profile for {}",
                added, profileKey, player.getName().getString());
        }
    }

    /**
     * Re-award {@code profileKey}'s (and the shared partition's) banked criteria onto this world's
     * tree, silently. Criteria earned in different worlds can add up to a finished advancement here;
     * that one is banked as earned and its criteria entry dropped.
     */
    public static void replayCriteria(ServerPlayer player, String profileKey) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerAdvancementManager mgr = server.getAdvancements();
        UUID uuid = player.getUUID();
        int[] replayed = {0};
        List<Runnable> finished = new ArrayList<>();
        silently(server, () -> {
            for (String partition : List.of(profileKey, GlobalAchievementStore.SHARED)) {
                for (Map.Entry<ResourceLocation, Set<String>> entry : GlobalCriteriaProgress.read(uuid, partition).entrySet()) {
                    AdvancementHolder holder = mgr.get(entry.getKey());
                    if (holder == null) continue; // removed mod/datapack: keep it, it may come back
                    for (String criterion : entry.getValue()) {
                        // A criterion the advancement no longer has is ignored by award().
                        if (player.getAdvancements().award(holder, criterion)) replayed[0]++;
                    }
                    if (player.getAdvancements().getOrStartProgress(holder).isDone()) {
                        ResourceLocation id = holder.id();
                        finished.add(() -> {
                            GlobalAchievementStore.append(uuid, partition, id);
                            GlobalCriteriaProgress.removeAdvancement(uuid, partition, id);
                        });
                    }
                }
            }
        });
        finished.forEach(Runnable::run);
        if (replayed[0] > 0) {
            LOGGER.info("[DungeonTrain] Replayed {} in-progress criteria from the {} profile for {} (silent)",
                replayed[0], profileKey, player.getName().getString());
        }
    }

    private static List<String> completedCriteria(AdvancementProgress progress) {
        List<String> held = new ArrayList<>();
        progress.getCompletedCriteria().forEach(held::add);
        return held;
    }

    // ---------------- Difficulty change ----------------

    /**
     * Mid-game difficulty change: put {@code to}'s advancements on the player's tree in place of
     * {@code from}'s, then re-derive everything that reads the tree, and resync the client so the
     * swap arrives as a reset rather than a stream of "Advancement Made!" toasts.
     */
    public static void swap(ServerPlayer player, String from, String to) {
        MinecraftServer server = player.getServer();
        if (server == null || from.equals(to)) return;
        swapTree(player, from, to);
        player.setData(ModDataAttachments.ADVANCEMENT_PARTITION.get(), to);
        // The same tail login runs after its replay — each reads the tree that was just swapped.
        silently(server, () -> {
            TabGateways.sync(player);
            TabCompleteAdvancements.checkAndGrant(player);
            CompletionistAdvancement.checkAndGrant(player);
        });
        StartAgainAdvancement.refreshCommandTree(player);
        LifeDisqualification.sync(player);
        // Write the swapped tree and reload it: vanilla's reload marks the next packet as the first,
        // which the client applies as a reset — the whole tree replaced, no toasts.
        PlayerAdvancements advancements = player.getAdvancements();
        advancements.save();
        advancements.reload(server.getAdvancements());
    }

    /** Bank the tree into {@code from}, clear its profile advancements, replay {@code to}'s. */
    private static void swapTree(ServerPlayer player, String from, String to) {
        absorb(player, from);
        absorbCriteria(player, from);
        int cleared;
        clearing = true;
        try {
            cleared = clearProfile(player);
        } finally {
            clearing = false;
        }
        replay(player, to);
        replayCriteria(player, to);
        LOGGER.info("[DungeonTrain] Advancement profile {} → {} for {} ({} cleared)",
            from, to, player.getName().getString(), cleared);
    }

    /**
     * Revoke every persisted advancement except the shared editor tab, the tab gateways (which
     * follow their originals — {@link TabGateways#sync}) and tab roots. A root is the tab's entry —
     * Dungeon Train's is "boarded the train", which only re-fires on the next boarding — so revoking
     * it mid-ride would hide the whole tab. Partial progress goes too: a profile's half-finished
     * multi-criteria advancement is that profile's, not the next one's.
     */
    private static int clearProfile(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return 0;
        PlayerAdvancements advancements = player.getAdvancements();
        int cleared = 0;
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            if (!shouldPersist(holder)) continue;
            if (GlobalAchievementStore.isShared(holder.id()) || TabGateways.isLinked(holder.id())) continue;
            if (holder.value().parent().isEmpty()) continue; // tab root
            boolean any = false;
            for (String criterion : holder.value().criteria().keySet()) {
                any |= advancements.revoke(holder, criterion);
            }
            if (any) cleared++;
        }
        return cleared;
    }

    /**
     * Run {@code awards} with chat announcements, Discord Presence's announce and the earn-event side
     * effects all stood down — for awards that restore what the player already earned.
     */
    private static void silently(MinecraftServer server, Runnable awards) {
        GameRules.BooleanValue announce = server.getGameRules().getRule(GameRules.RULE_ANNOUNCE_ADVANCEMENTS);
        boolean wasAnnouncing = announce.get();
        if (wasAnnouncing) announce.set(false, server);
        boolean wasReplaying = replaying;
        replaying = true;
        try {
            DiscordService.runWithAdvancementAnnounceSuppressed(awards);
        } finally {
            replaying = wasReplaying;
            if (wasAnnouncing) announce.set(true, server);
        }
    }
}
