package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.LifeDisqualifiedPacket;
import games.brennan.dungeontrain.player.PlayerRunState;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The advancements a single action can rule out for the rest of the current life, and the
 * per-player evaluation of which of them are ruled out right now.
 *
 * <p>Most "in a single life" advancements (carriage counts, biome tiers, no-chest streaks) reset
 * on death but can never be <em>lost</em> mid-life — a streak simply restarts. The ones listed in
 * {@link #RULES} are different: dealing damage, opening an ender chest or burning the starting
 * book closes them until the next life. The advancements screen greys those out and adds a red
 * line to their hint; a player who has chosen to track one is told the moment it is lost.</p>
 *
 * <p>Nothing here is stored. Every rule reads the per-life state the game already persists
 * ({@link PlayerRunState#damageDealt()}, {@link ModDataAttachments#OPENED_ENDER_CHEST_THIS_LIFE},
 * {@link ModDataAttachments#STARTING_BOOK_BURNED_THIS_LIFE}), so a relog mid-life re-derives the
 * same answer and respawn clears it for free. The client only ever holds a mirror, pushed by
 * {@link #sync} / {@link #notify}.</p>
 *
 * <p>An earned advancement is never reported as disqualified — the rule is moot once it is done.</p>
 *
 * <p>A second, softer family lives here too: {@link #STREAK_IDS}, the advancements that count
 * carriages (or chests) <em>since</em> the last chest opened / block broken / repeat chest. Those
 * are never lost for a life — the count just restarts — so they are never in {@link #current} and
 * never faded; the player can track them and is told when the streak resets instead.</p>
 */
public final class LifeDisqualification {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final ResourceLocation CONTAINED_LOOP = dt("dungeon_train/contained_loop");

    public static final ResourceLocation NO_CONTAINER_100 = dt("dungeon_train/no_container_100");
    public static final ResourceLocation NO_CONTAINER_1000 = dt("dungeon_train/no_container_1000");
    public static final ResourceLocation NO_BREAK_100 = dt("dungeon_train/no_break_100");
    public static final ResourceLocation NO_BREAK_1000 = dt("dungeon_train/no_break_1000");
    public static final ResourceLocation CHESTS_100_UNIQUE = dt("dungeon_train/chests_100_unique");

    /** The chest-free streak tiers — reset together by opening a chest or barrel. */
    public static final List<ResourceLocation> CONTAINER_STREAK = List.of(NO_CONTAINER_100, NO_CONTAINER_1000);
    /** The break-free streak tiers — reset together by breaking any block. */
    public static final List<ResourceLocation> BREAK_STREAK = List.of(NO_BREAK_100, NO_BREAK_1000);
    /** The distinct-chests streak — reset by opening the same chest twice. */
    public static final List<ResourceLocation> CHEST_STREAK = List.of(CHESTS_100_UNIQUE);

    /** Streak advancements: trackable, told about on reset, never ruled out for a life. */
    private static final List<ResourceLocation> STREAK_IDS = List.of(
        NO_CONTAINER_100, NO_CONTAINER_1000, NO_BREAK_100, NO_BREAK_1000, CHESTS_100_UNIQUE);

    /** Disqualification rules, in tree order. Insertion order is what the client receives. */
    private static final Map<ResourceLocation, Predicate<ServerPlayer>> RULES = buildRules();

    private LifeDisqualification() {}

    private static Map<ResourceLocation, Predicate<ServerPlayer>> buildRules() {
        Map<ResourceLocation, Predicate<ServerPlayer>> rules = new LinkedHashMap<>();
        Predicate<ServerPlayer> dealtDamage = p -> runState(p).damageDealt() > 0.0;
        rules.put(PacifistAdvancement.ID_100, dealtDamage);
        rules.put(PacifistAdvancement.ID_250, dealtDamage);
        rules.put(PacifistAdvancement.ID_1000, dealtDamage);
        rules.put(CONTAINED_LOOP,
            p -> p.getData(ModDataAttachments.OPENED_ENDER_CHEST_THIS_LIFE.get()));
        rules.put(FarStartAdvancement.ID,
            p -> p.getData(ModDataAttachments.STARTING_BOOK_BURNED_THIS_LIFE.get()));
        return java.util.Collections.unmodifiableMap(rules); // keeps insertion order (Map.copyOf would not)
    }

    /** The ids a single action can rule out for a life — the client's "trackable" set mirrors this. */
    public static List<ResourceLocation> disqualifiableIds() {
        return List.copyOf(RULES.keySet());
    }

    /** The streak advancements — see {@link #STREAK_IDS}. */
    public static List<ResourceLocation> streakIds() {
        return STREAK_IDS;
    }

    /**
     * A streak just restarted for {@code player}: tell the client which streak advancements it
     * affects, so any the player tracks can raise a "streak reset" toast. Earned tiers are
     * dropped — a reset can't take back what is already done. The disqualified set rides along
     * unchanged, as on every send.
     */
    public static void notifyStreakReset(ServerPlayer player, List<ResourceLocation> streak) {
        List<ResourceLocation> fresh = streak.stream()
            .filter(id -> !isEarned(player, id))
            .toList();
        if (fresh.isEmpty()) return;
        PacketDistributor.sendToPlayer(player, new LifeDisqualifiedPacket(current(player), List.of(), fresh));
    }

    /** Ids ruled out for {@code player}'s current life that the player has not already earned. */
    public static List<ResourceLocation> current(ServerPlayer player) {
        List<ResourceLocation> out = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Predicate<ServerPlayer>> rule : RULES.entrySet()) {
            if (rule.getValue().test(player) && !isEarned(player, rule.getKey())) {
                out.add(rule.getKey());
            }
        }
        return List.copyOf(out);
    }

    /** Push the full current set to the client — on login, respawn, or any time it may have drifted. */
    public static void sync(ServerPlayer player) {
        send(player, current(player), List.of());
    }

    /**
     * A rule just flipped for {@code player}: push the full set plus the ids that are newly lost,
     * so the client can raise a toast for any it is tracking. Earned ids are dropped — losing an
     * advancement you already hold is not news.
     */
    public static void notify(ServerPlayer player, List<ResourceLocation> newlyLost) {
        List<ResourceLocation> fresh = newlyLost.stream()
            .filter(id -> !isEarned(player, id))
            .toList();
        send(player, current(player), fresh);
        if (!fresh.isEmpty()) {
            LOGGER.debug("[DungeonTrain] {} disqualified this life from {}", player.getName().getString(), fresh);
        }
    }

    /** The pacifist tiers, as a list — the set the first damage dealt this life rules out together. */
    public static List<ResourceLocation> pacifistTiers() {
        return List.of(PacifistAdvancement.ID_100, PacifistAdvancement.ID_250, PacifistAdvancement.ID_1000);
    }

    private static void send(ServerPlayer player, List<ResourceLocation> all, List<ResourceLocation> fresh) {
        PacketDistributor.sendToPlayer(player, new LifeDisqualifiedPacket(all, fresh, List.of()));
    }

    private static boolean isEarned(ServerPlayer player, ResourceLocation id) {
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        AdvancementHolder holder = server.getAdvancements().get(id);
        if (holder == null) return false;
        return player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    private static PlayerRunState runState(ServerPlayer player) {
        return player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
    }

    private static ResourceLocation dt(String path) {
        return ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, path);
    }
}
