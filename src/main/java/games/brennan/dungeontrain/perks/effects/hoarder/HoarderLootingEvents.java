package games.brennan.dungeontrain.perks.effects.hoarder;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.enchanting.GetEnchantmentLevelEvent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wires {@link HoarderLooting}. {@code mixin/LivingEntityLootingPerkMixin} brackets a mob's
 * {@code dropFromLootTable}: when the killer is a player with the perk, {@link #enter} rolls the kill's
 * extra levels once and holds them on this thread; {@link #onGetLevels} adds them to Looting on that
 * player's main-hand item while the loot table reads it, even an item with no Looting of its own;
 * {@link #exit} restores whatever was held before. A hold also carries the game time it was made, so one
 * left behind by a throw never reaches a later read. While no hold is set anywhere, an enchantment read
 * costs one atomic read here.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class HoarderLootingEvents {

    /** Extra Looting for {@code player}'s main hand during one loot roll at {@code gameTime}. */
    record Hold(Player player, int levels, long gameTime) {}

    /** What {@link #exit} restores: the hold found at {@link #enter}, and whether enter set a new one. */
    record Bracket(Hold previous, boolean pushed) {}

    private static final Bracket NOTHING = new Bracket(null, false);
    private static final ThreadLocal<Hold> HELD = new ThreadLocal<>();
    /** Holds set on every thread; while 0 the enchantment-level hot path never touches the thread-local. */
    private static final AtomicInteger LIVE = new AtomicInteger();

    private HoarderLootingEvents() {}

    /** Called at the start of {@code victim}'s loot roll; returns the hold to restore at {@link #exit}. */
    public static Object enter(LivingEntity victim, DamageSource source) {
        if (victim instanceof Player || !(source.getEntity() instanceof ServerPlayer killer)) return NOTHING;
        int rank = Perks.rank(killer, HoarderLooting.PERK_ID);
        if (rank <= 0) return NOTHING;
        int levels = HoarderLooting.rollLevels(rank, killer.getRandom());
        if (levels <= 0) return NOTHING;
        Hold previous = HELD.get();
        HELD.set(new Hold(killer, levels, victim.level().getGameTime()));
        if (previous == null) LIVE.incrementAndGet();
        return new Bracket(previous, true);
    }

    /** Called when the loot roll returns: puts back the hold {@link #enter} found. */
    public static void exit(Object bracket) {
        if (!(bracket instanceof Bracket b) || !b.pushed()) return;
        if (b.previous() != null) {
            HELD.set(b.previous());
        } else if (HELD.get() != null) {
            HELD.remove();
            LIVE.decrementAndGet();
        }
    }

    @SubscribeEvent
    public static void onGetLevels(GetEnchantmentLevelEvent event) {
        if (LIVE.get() == 0) return;
        Hold hold = HELD.get();
        if (hold == null) return;
        if (hold.player().level().getGameTime() != hold.gameTime()) {
            // left behind by a loot roll that threw: drop it
            HELD.remove();
            LIVE.decrementAndGet();
            return;
        }
        if (event.getStack() != hold.player().getMainHandItem()) return;
        if (!event.isTargetting(Enchantments.LOOTING)) return;
        event.getHolder(Enchantments.LOOTING).ifPresent(looting -> raise(event.getEnchantments(), looting, hold.levels()));
    }

    static void raise(ItemEnchantments.Mutable levels, Holder<Enchantment> looting, int extra) {
        levels.set(looting, levels.getLevel(looting) + extra);
    }
}
