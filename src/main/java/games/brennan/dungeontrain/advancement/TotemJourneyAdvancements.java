package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.requirement.AdvancementRequirements;
import games.brennan.dungeontrain.event.AchievementEvents;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

import java.util.Map;
import java.util.Optional;

/**
 * The carriage-counted Dungeon Totems advancements: "Undead Commuter" (ride {@link #COMMUTE_CARTS} carriages
 * as a zombie, then be cured) and the "hold a full stack for {@link #STACK_CARTS} carriages" set
 * (Heart of Stone, Seeing Red, Party of One). Both are streaks on the effective travelled-carriage counter
 * ({@link AchievementEvents#effectiveTravelled}), shaped like the "no container" streak: remember the count
 * when the streak starts, award on the gap.
 *
 * <p>Dungeon Totems is reached by id only (its {@code zombified} effect, its totem items), never by class, so
 * this loads without it and simply never fires.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TotemJourneyAdvancements {

    public static final ResourceLocation ZOMBIFIED = ResourceLocation.fromNamespaceAndPath("dungeontotems", "zombified");

    public static final ResourceLocation UNDEAD_COMMUTER = dt("undead_commuter");
    public static final int COMMUTE_CARTS = 1000;
    public static final String COMMUTE_ACTION = "undead_commuter";

    public static final int STACK_CARTS = 500;

    /** Totem item → its advancement. Only totems that stack; a full stack is the item's own max stack size. */
    static final Map<ResourceLocation, ResourceLocation> STACK_ADVANCEMENTS = Map.of(
        totem("heartbreak_totem"), dt("heart_of_stone"),
        totem("rage_totem"), dt("seeing_red"),
        totem("loneliness_totem"), dt("party_of_one"));

    private TotemJourneyAdvancements() {}

    /** The gameplay action that grants the stack advancement for {@code totem}. */
    public static String stackAction(ResourceLocation totem) {
        return "full_stack_" + totem.getPath();
    }

    // ---- Undead Commuter ----

    @SubscribeEvent
    public static void onEffectAdded(MobEffectEvent.Added event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !isZombified(event.getEffectInstance().getEffect())) return;
        if (player.getData(ModDataAttachments.CARTS_AT_ZOMBIFIED.get()) >= 0) return; // already a zombie: keep the start
        player.setData(ModDataAttachments.CARTS_AT_ZOMBIFIED.get(), travelled(player));
    }

    /** A removal while alive is the cure (the curse has no timer and milk leaves it). */
    @SubscribeEvent
    public static void onEffectRemoved(MobEffectEvent.Remove event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getEffect() == null || !isZombified(event.getEffect())) return;
        int start = player.getData(ModDataAttachments.CARTS_AT_ZOMBIFIED.get());
        player.setData(ModDataAttachments.CARTS_AT_ZOMBIFIED.get(), -1);
        if (start < 0 || !player.isAlive()) return;
        if (commuted(start, travelled(player), AdvancementRequirements.intValue(UNDEAD_COMMUTER, COMMUTE_CARTS))) {
            ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, COMMUTE_ACTION);
        }
    }

    static boolean commuted(int start, int now, int threshold) {
        return start >= 0 && now - start >= threshold;
    }

    private static boolean isZombified(Holder<MobEffect> effect) {
        return effect.is(ZOMBIFIED);
    }

    // ---- full stacks ----

    /** Once a second, from {@link AchievementEvents#onPlayerTick}. */
    public static void checkHeldStack(ServerPlayer player) {
        String was = player.getData(ModDataAttachments.HELD_TOTEM_STACK.get());
        Optional<ResourceLocation> held = heldFullStack(player);
        if (held.isEmpty()) {
            if (!was.isEmpty()) player.setData(ModDataAttachments.HELD_TOTEM_STACK.get(), "");
            return;
        }
        int now = travelled(player);
        HeldStack streak = HeldStack.parse(was);
        if (streak == null || !streak.totem().equals(held.get())) {
            player.setData(ModDataAttachments.HELD_TOTEM_STACK.get(), new HeldStack(held.get(), now).encode());
            return;
        }
        ResourceLocation advancement = STACK_ADVANCEMENTS.get(streak.totem());
        if (now - streak.startCarts() >= AdvancementRequirements.intValue(advancement, STACK_CARTS)) {
            ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, stackAction(streak.totem()));
        }
    }

    private static Optional<ResourceLocation> heldFullStack(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (STACK_ADVANCEMENTS.containsKey(id)) return Optional.of(id);
        }
        return Optional.empty();
    }

    /** One held stack: which totem, and the carriage count when the player took it up. */
    record HeldStack(ResourceLocation totem, int startCarts) {
        String encode() {
            return totem + "@" + startCarts;
        }

        static HeldStack parse(String s) {
            int at = s.lastIndexOf('@');
            if (at <= 0) return null;
            ResourceLocation id = ResourceLocation.tryParse(s.substring(0, at));
            try {
                return id == null ? null : new HeldStack(id, Integer.parseInt(s.substring(at + 1)));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private static int travelled(ServerPlayer player) {
        return AchievementEvents.effectiveTravelled(player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()));
    }

    private static ResourceLocation dt(String path) {
        return ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train/" + path);
    }

    private static ResourceLocation totem(String path) {
        return ResourceLocation.fromNamespaceAndPath("dungeontotems", path);
    }
}
