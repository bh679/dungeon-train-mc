package games.brennan.dungeontrain.perks.effects.hoarder;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.KillCredit;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import org.slf4j.Logger;

/**
 * Wires {@link HoarderLooting} to mob deaths: a kill credited to a player with the Looting perk active
 * adds one extra item to the drop list, rolled once per kill. The drop is added to the event's own list,
 * so it spawns in the same level as the mob's own drops (plot space on a carriage).
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class HoarderLootingEvents {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Positional jitter for the extra drop, matching {@code Block.popResource}. */
    private static final double DROP_JITTER = 0.25;

    private HoarderLootingEvents() {}

    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim instanceof Player) return;
        if (!(victim.level() instanceof ServerLevel level)) return;
        Entity killer = KillCredit.resolveKiller(victim, event.getSource()).orElse(null);
        if (!(killer instanceof ServerPlayer player)) return;
        int rank = Perks.rank(player, HoarderLooting.PERK_ID);
        if (rank <= 0) return;

        RandomSource random = level.getRandom();
        if (!HoarderLooting.rollsExtra(rank, random)) return;
        ItemStack extra = HoarderLooting.pickExtraFromEntities(event.getDrops(), random);
        if (extra.isEmpty()) return;

        event.getDrops().add(extraDrop(level, victim, extra));
        LOGGER.debug("[perk.looting] {} killed {} at rank {} -> +1 {}",
            player.getGameProfile().getName(), victim.getType().getDescriptionId(), rank, extra);
    }

    private static ItemEntity extraDrop(ServerLevel level, LivingEntity victim, ItemStack stack) {
        RandomSource random = level.getRandom();
        double x = victim.getX() + Mth.nextDouble(random, -DROP_JITTER, DROP_JITTER);
        double y = victim.getY(0.5) + Mth.nextDouble(random, -DROP_JITTER, DROP_JITTER);
        double z = victim.getZ() + Mth.nextDouble(random, -DROP_JITTER, DROP_JITTER);
        ItemEntity drop = new ItemEntity(level, x, y, z, stack);
        drop.setDefaultPickUpDelay();
        return drop;
    }
}
