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
 */
public final class LifeDisqualification {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final ResourceLocation CONTAINED_LOOP = dt("dungeon_train/contained_loop");

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
        PacketDistributor.sendToPlayer(player, new LifeDisqualifiedPacket(all, fresh));
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
