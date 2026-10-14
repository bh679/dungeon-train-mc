package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.requirement.AdvancementRequirements;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;

/**
 * "All at Once" / "The Challenge" (100 / 1,000 carriages) — every Challenges-tab "do without" rule
 * held together for one life. Fail one, fail all:
 *
 * <ul>
 *   <li>no apple or edible backpack, nothing eaten but melon, no armor (the {@link LifeChallengeAdvancements} flags);</li>
 *   <li>no block broken, vases included, and no chest or barrel opened or broken;</li>
 *   <li>no damage dealt (Pacifist) and no ender chest opened (Contained Loop, without the dying).</li>
 * </ul>
 *
 * <p>The individual challenges are untouched. Breaking a block or opening a chest only restarts
 * their streaks, but it rules these tiers out for the rest of the life: every rule-break site calls
 * {@link #markLost}, which sets {@link ModDataAttachments#ALL_AT_ONCE_LOST_THIS_LIFE} (cleared on
 * respawn by {@link LifeChallengeAdvancements#resetForNewLife}). Thresholds live in each tier's
 * {@code dungeontrain:code_granted} criterion, read through {@link AdvancementRequirements}.</p>
 */
public final class AllAtOnceAdvancement {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final ResourceLocation ALL_AT_ONCE = dt("dungeon_train/all_at_once");
    public static final ResourceLocation THE_CHALLENGE = dt("dungeon_train/the_challenge");

    public static final List<ResourceLocation> TIERS = List.of(ALL_AT_ONCE, THE_CHALLENGE);

    private record Tier(ResourceLocation id, int defaultThreshold) {
        int threshold() {
            return AdvancementRequirements.intValue(id, defaultThreshold);
        }
    }

    private static final List<Tier> TIER_THRESHOLDS = List.of(
        new Tier(ALL_AT_ONCE, 100),
        new Tier(THE_CHALLENGE, 1000));

    private AllAtOnceAdvancement() {}

    private static ResourceLocation dt(String path) {
        return ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, path);
    }

    // ---- pure rules (unit-tested) ----

    /** Every rule still held this life, the tier's carriage count reached. */
    static boolean shouldGrant(int travelledCarriagesAbs, int threshold, boolean anyRuleBroken) {
        return !anyRuleBroken && travelledCarriagesAbs >= threshold;
    }

    // ---- live ----

    /**
     * Has {@code player} broken any of the rules this life? The dedicated flag covers them all; the
     * older per-life flags and the damage tally are re-read too, so the tiers can never be granted
     * past a rule-break site that forgot to call {@link #markLost}.
     */
    public static boolean anyRuleBroken(ServerPlayer player) {
        return player.getData(ModDataAttachments.ALL_AT_ONCE_LOST_THIS_LIFE.get())
            || player.getData(ModDataAttachments.HELD_APPLE_THIS_LIFE.get())
            || player.getData(ModDataAttachments.ATE_NON_MELON_THIS_LIFE.get())
            || player.getData(ModDataAttachments.WORE_ARMOR_THIS_LIFE.get())
            || player.getData(ModDataAttachments.OPENED_ENDER_CHEST_THIS_LIFE.get())
            || player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).damageDealt() > 0.0;
    }

    /** Award every tier this life has reached with every rule still held. */
    public static void checkAndGrant(ServerPlayer player, int travelledCarriagesAbs) {
        if (anyRuleBroken(player)) return;
        for (Tier tier : TIER_THRESHOLDS) {
            if (shouldGrant(travelledCarriagesAbs, tier.threshold(), false)) grant(player, tier.id());
        }
    }

    /**
     * One of the rules just broke. The first time this life, rule both tiers out and tell the client
     * (a tracked tier toasts, the screen greys them out). Cheap to call on every break or open.
     */
    public static void markLost(ServerPlayer player) {
        if (player.getData(ModDataAttachments.ALL_AT_ONCE_LOST_THIS_LIFE.get())) return;
        player.setData(ModDataAttachments.ALL_AT_ONCE_LOST_THIS_LIFE.get(), Boolean.TRUE);
        LifeDisqualification.notify(player, TIERS);
    }

    private static void grant(ServerPlayer player, ResourceLocation id) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        AdvancementHolder self = server.getAdvancements().get(id);
        if (self == null || player.getAdvancements().getOrStartProgress(self).isDone()) return;
        boolean granted = false;
        for (String key : self.value().criteria().keySet()) {
            if (player.getAdvancements().award(self, key)) granted = true;
        }
        if (granted) LOGGER.info("[DungeonTrain] Granted challenge {} to {}", id, player.getName().getString());
    }
}
