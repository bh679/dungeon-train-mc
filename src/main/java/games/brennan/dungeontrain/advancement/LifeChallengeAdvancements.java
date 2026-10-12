package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.requirement.AdvancementRequirements;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.attachment.AttachmentType;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The Challenges tab's "do without" advancements, each a carriage milestone in a single life that one
 * habit rules out for the rest of that life — the {@link PacifistAdvancement} pattern with a per-life
 * flag in place of damage dealt:
 *
 * <ul>
 *   <li>"An apple a day keeps the doctor away." / "Self Medicated" — never an apple or an edible
 *       backpack in the inventory ({@link ModDataAttachments#HELD_APPLE_THIS_LIFE});</li>
 *   <li>The Last Melon and So you got three melons? (100 / 1,000) — eat nothing but melon ({@link ModDataAttachments#ATE_NON_MELON_THIS_LIFE});</li>
 *   <li>Naked and Afraid / Naked and Unafraid — never wear armor; an elytra is allowed
 *       ({@link ModDataAttachments#WORE_ARMOR_THIS_LIFE}).</li>
 * </ul>
 *
 * <p>The flags are set by {@code LifeChallengeEvents} and cleared on respawn. Each tier's JSON carries a
 * {@code dungeontrain:code_granted} criterion holding its threshold, read through
 * {@link AdvancementRequirements} so the editor and relay overrides can rebalance it. Lost challenges
 * are greyed out through {@link LifeDisqualification}.</p>
 */
public final class LifeChallengeAdvancements {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final ResourceLocation APPLE_A_DAY = dt("dungeon_train/apple_a_day");
    public static final ResourceLocation SELF_MEDICATED = dt("dungeon_train/self_medicated");
    public static final ResourceLocation LAST_MELON = dt("dungeon_train/last_melon");
    /** "So you got three melons?" — The Last Melon's next tier. */
    public static final ResourceLocation THREE_MELONS = dt("dungeon_train/three_melons");
    public static final ResourceLocation NAKED_AND_AFRAID = dt("dungeon_train/naked_and_afraid");
    public static final ResourceLocation NAKED_AND_UNAFRAID = dt("dungeon_train/naked_and_unafraid");

    public static final List<ResourceLocation> APPLE_TIERS = List.of(APPLE_A_DAY, SELF_MEDICATED);
    public static final List<ResourceLocation> MELON_TIERS = List.of(LAST_MELON, THREE_MELONS);
    public static final List<ResourceLocation> NAKED_TIERS = List.of(NAKED_AND_AFRAID, NAKED_AND_UNAFRAID);

    /** Apples: vanilla's three and BetterNether's black apples. The black apple seed is not an apple. */
    public static final Set<String> APPLES = Set.of(
        "minecraft:apple", "minecraft:golden_apple", "minecraft:enchanted_golden_apple",
        "betternether:black_apple", "betternether:stalagnate_bowl_apple");
    public static final Set<String> BACKPACKS = Set.of(
        "ediblebackpacks:edible_backpack", "ediblebackpacks:golden_edible_backpack");
    public static final Set<String> MELONS = Set.of("minecraft:melon_slice", "minecraft:glistering_melon_slice");

    private record Tier(ResourceLocation id, int defaultThreshold, Supplier<AttachmentType<Boolean>> lostFlag) {
        int threshold() {
            return AdvancementRequirements.intValue(id, defaultThreshold);
        }
    }

    private static final List<Tier> TIERS = List.of(
        new Tier(APPLE_A_DAY, 100, ModDataAttachments.HELD_APPLE_THIS_LIFE),
        new Tier(SELF_MEDICATED, 1000, ModDataAttachments.HELD_APPLE_THIS_LIFE),
        new Tier(LAST_MELON, 100, ModDataAttachments.ATE_NON_MELON_THIS_LIFE),
        new Tier(THREE_MELONS, 1000, ModDataAttachments.ATE_NON_MELON_THIS_LIFE),
        new Tier(NAKED_AND_AFRAID, 100, ModDataAttachments.WORE_ARMOR_THIS_LIFE),
        new Tier(NAKED_AND_UNAFRAID, 1000, ModDataAttachments.WORE_ARMOR_THIS_LIFE));

    private LifeChallengeAdvancements() {}

    private static ResourceLocation dt(String path) {
        return ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, path);
    }

    // ---- pure rules (unit-tested) ----

    static boolean shouldGrant(int travelledCarriagesAbs, boolean lostThisLife, int threshold) {
        return !lostThisLife && travelledCarriagesAbs >= threshold;
    }

    /** Does holding this item rule out the apple challenges? */
    public static boolean isAppleOrBackpack(String itemId) {
        return APPLES.contains(itemId) || BACKPACKS.contains(itemId);
    }

    /** Does finishing eating this rule out The Last Melon? Only edible items reach here. */
    public static boolean breaksMelonDiet(String itemId) {
        return !MELONS.contains(itemId);
    }

    // ---- live ----

    /** Award every tier this life has reached without losing it. */
    public static void checkAndGrant(ServerPlayer player, int travelledCarriagesAbs) {
        for (Tier tier : TIERS) {
            if (shouldGrant(travelledCarriagesAbs, player.getData(tier.lostFlag().get()), tier.threshold())) {
                grant(player, tier.id());
            }
        }
    }

    /**
     * Set a per-life flag; the first time it is set this life, tell the client those challenges are
     * lost (a tracked one toasts, the screen greys it out).
     */
    public static void markLost(ServerPlayer player, Supplier<AttachmentType<Boolean>> flag, List<ResourceLocation> tiers) {
        if (player.getData(flag.get())) return;
        player.setData(flag.get(), Boolean.TRUE);
        LifeDisqualification.notify(player, tiers);
    }

    /** Respawn: a new life starts with every challenge open again. */
    public static void resetForNewLife(ServerPlayer player) {
        player.setData(ModDataAttachments.HELD_APPLE_THIS_LIFE.get(), Boolean.FALSE);
        player.setData(ModDataAttachments.ATE_NON_MELON_THIS_LIFE.get(), Boolean.FALSE);
        player.setData(ModDataAttachments.WORE_ARMOR_THIS_LIFE.get(), Boolean.FALSE);
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
